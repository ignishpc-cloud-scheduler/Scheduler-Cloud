#!/bin/bash
set -euo pipefail

exec > >(tee /var/log/user-data.log | logger -t user-data -s 2>/dev/console) 2>&1
echo "[executor-setup] starting..."

# ── Instalar dependencias  ──────────────────────────────────────────────────────
if grep -qi "Amazon Linux" /etc/os-release; then
  echo "[executor-setup] detected Amazon Linux"
  dnf -y install tar gzip docker awscli-2 nfs-utils iproute
else
  echo "[executor-setup] non-Amazon Linux, using apt fallback"
  apt-get update -y
  apt-get install -y docker.io awscli tar gzip curl nfs-common iproute2
fi

systemctl enable --now docker
command -v aws    >/dev/null 2>&1 || { echo "[executor-setup] ERROR: aws not found";    exit 1; }
command -v docker >/dev/null 2>&1 || { echo "[executor-setup] ERROR: docker not found"; exit 1; }

# ── TCP keepalive agresivo (defensa en profundidad) ───────────────────────────
sysctl -w net.ipv4.tcp_keepalive_time=30   || true
sysctl -w net.ipv4.tcp_keepalive_intvl=5   || true
sysctl -w net.ipv4.tcp_keepalive_probes=3  || true

# ── Template variables (sustituidas en UserDataBuilder) ───────────────────────────
export REGION='{{REGION}}'
export JOB_ID='{{JOB_ID}}'
export CONTAINER_NAME='{{CONTAINER_NAME}}'
export BUCKET='{{BUCKET}}'
export BUNDLE_KEY='{{BUNDLE_KEY}}'
export IMAGE='{{IMAGE}}'
export EFS_IP='{{EFS_MOUNT_IP}}'
export TOTAL_EXECUTORS='{{TOTAL_EXECUTORS}}'

# ── Obtener metadata de la instancia (IMDSv2) ─────────────────────────────────
TOKEN=$(curl -fsS -X PUT "http://169.254.169.254/latest/api/token" \
  -H "X-aws-ec2-metadata-token-ttl-seconds: 21600")

IID=$(curl -fsS -H "X-aws-ec2-metadata-token: $TOKEN" \
  http://169.254.169.254/latest/meta-data/instance-id || echo "unknown")
PRIVATE_IP=$(curl -fsS -H "X-aws-ec2-metadata-token: $TOKEN" \
  http://169.254.169.254/latest/meta-data/local-ipv4 || true)

echo "[executor-setup] instance-id=$IID private-ip=$PRIVATE_IP"

# ── Montaje EFS ───────────────────────────────────────────────────────────────
echo "[executor-setup] mounting EFS via IP $EFS_IP"
mkdir -p /ignis/dfs

for i in $(seq 1 20); do
  if mount -t nfs4 \
      -o nfsvers=4.1,rsize=1048576,wsize=1048576,hard,timeo=600,retrans=2,noresvport \
      "${EFS_IP}:/" /ignis/dfs 2>/tmp/efs-mount-err.txt; then
    echo "[executor-setup] EFS mounted on attempt $i"
    break
  fi
  echo "[executor-setup] EFS mount attempt $i failed: $(cat /tmp/efs-mount-err.txt)"
  sleep 10
done

if ! mountpoint -q /ignis/dfs; then
  echo "[executor-setup] ERROR: EFS mount failed after 20 attempts"
  exit 1
fi

mkdir -p /ignis/dfs/payload

# ── Descargar y extraer bundle ───────────────────────────────────────────────
echo "[executor-setup] downloading bundle s3://$BUCKET/$BUNDLE_KEY"
aws --region "$REGION" s3 cp "s3://$BUCKET/$BUNDLE_KEY" /tmp/bundle.tar.gz
tar -xzf /tmp/bundle.tar.gz -C /
find /ignis/dfs -not -path '*/ssh/*' -exec chmod 777 {} \; 2>/dev/null || true

echo "[executor-setup] syncing large payload files from S3..."
aws --region "$REGION" s3 sync \
  "s3://${BUCKET}/jobs/${JOB_ID}/payload/large/" "/ignis/dfs/payload/" --quiet || true

# ── Pull imagen Docker ────────────────────────────────────────────────────────────────
echo "[executor-setup] pulling image $IMAGE"
docker pull "$IMAGE"

# ── Directorios de trabajo ────────────────────────────────────────────────────
EXECUTOR_DIR="/ignis/dfs/payload/${JOB_ID}/tmp/${CONTAINER_NAME}"
JOB_DIR="/ignis/dfs/payload/${JOB_ID}"
JOB_SOCKETS_DIR="${JOB_DIR}/sockets"

mkdir -p "$EXECUTOR_DIR" "$JOB_SOCKETS_DIR"
chmod 777 "$JOB_DIR" "$JOB_SOCKETS_DIR"

# ── Keypair SSH compartida vía EFS ────────────────────────────────────────────
SHARED_SSH=/ignis/dfs/shared-ssh
mkdir -p "$SHARED_SSH"

LOCK_FILE="$SHARED_SSH/.lock"
KEY_FILE="$SHARED_SSH/id_rsa"
PUB_FILE="$SHARED_SSH/id_rsa.pub"
DONE_FILE="$SHARED_SSH/.done"

exec 9>"$LOCK_FILE"
flock -x 9

if [ ! -f "$DONE_FILE" ]; then
    echo "[executor-setup] generating shared SSH keypair"
    rm -f "$KEY_FILE" "$PUB_FILE"
    ssh-keygen -t rsa -b 2048 -N "" -f "$KEY_FILE" -C "ignis-shared"
    chmod 600 "$KEY_FILE"
    chmod 644 "$PUB_FILE"
    touch "$DONE_FILE"
fi

flock -u 9
exec 9>&-

# ── Publicar el número total de executors (solo el primero) ───────────────────
TOTAL_DONE_FILE="$SHARED_SSH/.total-done"

exec 9>"$LOCK_FILE"
flock -x 9

if [ ! -f "$TOTAL_DONE_FILE" ]; then
    echo "[executor-setup] publishing total executors = $TOTAL_EXECUTORS"
    printf '{"total":%s,"job":"%s"}\n' "$TOTAL_EXECUTORS" "$JOB_ID" > /tmp/total.json
    if aws --region "$REGION" s3 cp /tmp/total.json \
        "s3://${BUCKET}/jobs/${JOB_ID}/executors/total.json"; then
        touch "$TOTAL_DONE_FILE"
        echo "[executor-setup] total.json uploaded"
    else
        echo "[executor-setup] WARNING: failed to upload total.json, another executor will retry"
    fi
fi

flock -u 9
exec 9>&-

# Esperar propagación NFS de los metadatos
for i in $(seq 1 10); do
    [ -f "$KEY_FILE" ] && [ -f "$PUB_FILE" ] && break
    sleep 1
done
[ -f "$KEY_FILE" ] || { echo "[executor-setup] ERROR: shared keypair never appeared"; exit 1; }

# ── Preparar .ssh por executor para montar en el container ────────────────────
HOST_SSH_DIR=/ignis/dfs/payload/${JOB_ID}/ssh/${CONTAINER_NAME}
mkdir -p "$HOST_SSH_DIR"
cp "$KEY_FILE" "$HOST_SSH_DIR/id_rsa"
cp "$PUB_FILE" "$HOST_SSH_DIR/id_rsa.pub"
cp "$PUB_FILE" "$HOST_SSH_DIR/authorized_keys"
chmod 700 "$HOST_SSH_DIR"
chmod 600 "$HOST_SSH_DIR/id_rsa"
chmod 644 "$HOST_SSH_DIR/id_rsa.pub" "$HOST_SSH_DIR/authorized_keys"

cat > "$HOST_SSH_DIR/config" <<EOF
Host *
    Port 1963
    StrictHostKeyChecking no
    UserKnownHostsFile /dev/null
    LogLevel ERROR
EOF
chmod 644 "$HOST_SSH_DIR/config"

# ── launch executor container ─────────────────────────────────────────────────
echo "[executor-setup] launching executor container $CONTAINER_NAME"
docker rm -f "$CONTAINER_NAME" 2>/dev/null || true

docker run -d \
  --name "$CONTAINER_NAME" \
  --network host \
  -v /ignis/dfs:/ignis/dfs \
  -v "${JOB_SOCKETS_DIR}:${JOB_SOCKETS_DIR}" \
  -v "${HOST_SSH_DIR}:/root/.ssh:ro" \
  -e IGNIS_SCHEDULER_ENV_JOB="$JOB_ID" \
  -e IGNIS_SCHEDULER_ENV_CONTAINER="$CONTAINER_NAME" \
  -e IGNIS_WDIR="/ignis/dfs/payload" \
  -e IGNIS_JOB_DIR="$JOB_DIR" \
  -e IGNIS_JOB_SOCKETS="$JOB_SOCKETS_DIR" \
  -e IGNIS_EXECUTOR_DIRECTORY="$EXECUTOR_DIR" \
  -e IGNIS_EXECUTOR_WDIR="$EXECUTOR_DIR" \
  -e MPICH_SERVICE="$PRIVATE_IP" \
  -e MPICH_LIST_PORTS="20000,20001,20002,20003,20004" \
  -e MPIR_CVAR_ASYNC_PROGRESS=1 \
  {{EXECUTOR_ENV}}"$IMAGE" \
  {{EXECUTOR_CMD}}

# ── Esperar a que el ignis-sshserver esté escuchando el puerto 1963 ────────────────────────────────────────────
for i in $(seq 1 60); do
  if bash -c "echo > /dev/tcp/127.0.0.1/1963" 2>/dev/null; then
    echo "[executor-setup] sshserver ready after $((i * 2))s"
    break
  fi
  sleep 2
done

if ! bash -c "echo > /dev/tcp/127.0.0.1/1963" 2>/dev/null; then
  echo "[executor-setup] WARNING: sshserver port 1963 did not open in time"
  docker logs "$CONTAINER_NAME" 2>&1 || true
fi

# ── Inyectar la pubkey compartida en el authorized_keys que sshd realmente lee ──
IGNIS_AUTH=/ignis/dfs/payload/${JOB_ID}/tmp/${CONTAINER_NAME}/ssh/authorized_keys

for i in $(seq 1 30); do
    [ -f "$IGNIS_AUTH" ] && break
    sleep 1
done

if [ ! -f "$IGNIS_AUTH" ]; then
    echo "[executor-setup] ERROR: $IGNIS_AUTH never appeared"
elif ! grep -qFf "$PUB_FILE" "$IGNIS_AUTH" 2>/dev/null; then
    cat "$PUB_FILE" >> "$IGNIS_AUTH"
    echo "[executor-setup] shared pubkey added to $IGNIS_AUTH"
fi

# ── Signal readiness al backend ───────────────────────────────────────────────
READY_KEY="jobs/${JOB_ID}/executors/${CONTAINER_NAME}/ready.json"
printf '{"container":"%s","instance":"%s","status":"ready"}\n' \
  "$CONTAINER_NAME" "$IID" > /tmp/ready.json

echo "[executor-setup] uploading ready marker: s3://$BUCKET/$READY_KEY"
aws --region "$REGION" s3 cp /tmp/ready.json "s3://$BUCKET/$READY_KEY"

# Log de setup disponible mientras el executor sigue corriendo
aws --region "$REGION" s3 cp /var/log/user-data.log \
  "s3://${BUCKET}/jobs/${JOB_ID}/logs/${CONTAINER_NAME}-setup.log" || true

# ── Esperar a que el container termine y subir logs finales ───────────────────
echo "[executor-setup] waiting for executor container to exit..."
set +e
docker wait "$CONTAINER_NAME"
CONTAINER_RC=$?
set -e
echo "[executor-setup] container exited with rc=$CONTAINER_RC"

# ── DESBLOQUEAR AL DRIVER ─────────────────────────────────────────────────────
echo "[executor-setup] killing established TCP connections on port 1963 to unblock driver"
ss -K 'sport = :1963' 2>/dev/null || echo "[executor-setup] WARNING: ss -K failed (kernel without socket-destroy support?)"
# Verificación informativa
ss -tan 'sport = :1963' 2>/dev/null || true

docker logs "$CONTAINER_NAME" > /tmp/executor-container.log 2>&1 || true
aws --region "$REGION" s3 cp /tmp/executor-container.log \
  "s3://${BUCKET}/jobs/${JOB_ID}/logs/${CONTAINER_NAME}-container.log" || true
aws --region "$REGION" s3 cp /var/log/user-data.log \
  "s3://${BUCKET}/jobs/${JOB_ID}/logs/${CONTAINER_NAME}-final.log" || true

  # ── Subir el contenido de /ignis/dfs/payload a S3 ─────────────────────────────
PAYLOAD_KEY="jobs/${JOB_ID}/executors/${CONTAINER_NAME}/payload/"
echo "[executor-setup] syncing /ignis/dfs/payload to s3://${BUCKET}/${PAYLOAD_KEY}"

aws --region "$REGION" s3 sync \
  /ignis/dfs/payload \
  "s3://${BUCKET}/${PAYLOAD_KEY}" \
  --no-progress 2>&1 || echo "[executor-setup] WARNING: payload sync failed"

echo "[executor-setup] payload sync finished"

# ── Señal para indicar terminación al driver ───────────────────────────────────────────────
DONE_KEY="jobs/${JOB_ID}/executors/${CONTAINER_NAME}/done.json"
printf '{"container":"%s","instance":"%s","rc":%s,"status":"done"}\n' \
  "$CONTAINER_NAME" "$IID" "$CONTAINER_RC" > /tmp/done.json

echo "[executor-setup] uploading done marker: s3://$BUCKET/$DONE_KEY"
aws --region "$REGION" s3 cp /tmp/done.json "s3://$BUCKET/$DONE_KEY" || true
echo "[executor-setup] done marker uploaded"

echo "[executor-setup] done"