#!/bin/bash
set -euo pipefail

exec > >(tee /var/log/user-data.log | logger -t user-data -s 2>/dev/console) 2>&1
echo "[user-data] starting..."

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
command -v aws >/dev/null 2>&1 || { echo "[user-data] ERROR: aws not found"; exit 1; }
command -v docker >/dev/null 2>&1 || { echo "[user-data] ERROR: docker not found"; exit 1; }

sysctl -w kernel.yama.ptrace_scope=0 2>/dev/null || true

# ── Template variables (sustituidas en UserDataBuilder) ──────────────────────────────
export REGION='{{REGION}}'
export BUCKET='{{BUCKET}}'
export JOB_ID='{{JOB_ID}}'
export JOB_NAME='{{JOB_NAME}}'
export BUNDLE_KEY='{{BUNDLE_KEY}}'
export IMAGE='{{IMAGE}}'
export CMD='{{CMD}}'
export IGNIS_SUBNET_ID='{{SUBNET_ID}}'
export IGNIS_SG_ID='{{SG_ID}}'
export IGNIS_AMI='{{AMI}}'
export IGNIS_INSTANCE_TYPE='{{INSTANCE_TYPE}}'
export EFS_MOUNT_IP='{{EFS_MOUNT_IP}}'

# ── Montaje de EFS ──────────────────────────────────────────────────────
echo "[user-data] mounting EFS via IP $EFS_MOUNT_IP"
mkdir -p /ignis/dfs

EFS_MOUNTED=0
for i in $(seq 1 20); do
  if mount -t nfs4 \
      -o nfsvers=4.1,rsize=1048576,wsize=1048576,hard,timeo=600,retrans=2,noresvport \
      "${EFS_MOUNT_IP}:/" /ignis/dfs 2>/tmp/efs-mount-err.txt; then
    echo "[user-data] EFS mounted successfully on attempt $i"
    EFS_MOUNTED=1
    break
  fi
  echo "[user-data] EFS mount attempt $i failed: $(cat /tmp/efs-mount-err.txt)"
  sleep 10
done

if [ "$EFS_MOUNTED" -ne 1 ]; then
  echo "[user-data] ERROR: EFS mount failed"
  exit 1
fi

mkdir -p /ignis/dfs/payload /ignis/dfs/output
find /ignis/dfs -not -path '*/ssh/*' -exec chmod 777 {} \; 2>/dev/null || true

# ── Obtener metadatos de la instancia ──────────────────────────────────────
IID="unknown"
TOKEN=$(curl -fsS -X PUT "http://169.254.169.254/latest/api/token" \
  -H "X-aws-ec2-metadata-token-ttl-seconds: 21600" || true)

if [ -n "$TOKEN" ]; then
  IID=$(curl -fsS -H "X-aws-ec2-metadata-token: $TOKEN" \
    http://169.254.169.254/latest/meta-data/instance-id || echo "unknown")
else
  IID=$(curl -fsS http://169.254.169.254/latest/meta-data/instance-id || echo "unknown")
fi
echo "[user-data] instance-id=$IID"

# ── Descargar y extraer ──────────────────────────────────────
echo "[user-data] downloading bundle s3://$BUCKET/$BUNDLE_KEY"
aws --region "$REGION" s3 cp "s3://$BUCKET/$BUNDLE_KEY" /tmp/bundle.tar.gz
tar -xzf /tmp/bundle.tar.gz -C /
find /ignis/dfs -not -path '*/ssh/*' -exec chmod 777 {} \; 2>/dev/null || true

echo "[user-data] downloading large payload files from S3..."
aws --region "$REGION" s3 sync "s3://${BUCKET}/jobs/${JOB_ID}/payload/large/" "/ignis/dfs/payload/" --quiet || true

# ── Pull de la imagen Docker ──────────────────────────────────────
echo "[user-data] pulling image $IMAGE"
docker pull "$IMAGE"

# ── Restore job metadata for the backend (runtime mode) ───────────────────────
mkdir -p /var/tmp/ignis-cloud/jobs
aws --region "$REGION" s3 cp "s3://$BUCKET/jobs/$JOB_ID/job-meta.json" "/var/tmp/ignis-cloud/jobs/$JOB_ID.json"

# ── Ejecutar el contenedor del driver ──────────────────────────────────────
START_TS=$(date -u +"%Y-%m-%dT%H:%M:%SZ" 2>/dev/null || echo "")

# ── WATCHER DE DESBLOQUEO DEL DRIVER ──────────────────────────────────────────
DRIVER_UNBLOCK_LOG=/var/log/driver-unblock.log
:> "$DRIVER_UNBLOCK_LOG"

(
  exec >>"$DRIVER_UNBLOCK_LOG" 2>&1
  echo "[unblock] watcher started at $(date -u +%FT%TZ)"

  EXEC_PREFIX="jobs/${JOB_ID}/executors"
  TOTAL_KEY="s3://${BUCKET}/${EXEC_PREFIX}/total.json"
  S3_LOG="s3://${BUCKET}/jobs/${JOB_ID}/driver-unblock.log"

  push_log() { aws --region "$REGION" s3 cp "$DRIVER_UNBLOCK_LOG" "$S3_LOG" >/dev/null 2>&1 || true; }
  push_log

  W=0; TOTAL=""
  while [ "$W" -lt 600 ]; do
    if aws --region "$REGION" s3 cp "$TOTAL_KEY" /tmp/unblock-total.json >/dev/null 2>&1; then
      TOTAL=$(jq -r '.total // empty' /tmp/unblock-total.json 2>/dev/null || true)
      [ -n "${TOTAL:-}" ] && { echo "[unblock] total=$TOTAL"; break; }
    fi
    sleep 10; W=$((W+10))
  done
  [ -z "${TOTAL:-}" ] && { echo "[unblock] no total.json, exit"; push_log; exit 0; }
  push_log

  W=0
  while [ "$W" -lt 2400 ]; do
    DONE_COUNT=$(aws --region "$REGION" s3 ls "s3://${BUCKET}/${EXEC_PREFIX}/" --recursive 2>/dev/null | grep -c '/done\.json$' || true)
    [ "$DONE_COUNT" -ge "$TOTAL" ] && { echo "[unblock] all $TOTAL done after ${W}s"; break; }
    sleep 10; W=$((W+10))
  done
  [ "${DONE_COUNT:-0}" -lt "$TOTAL" ] && { echo "[unblock] timeout, exit"; push_log; exit 0; }
  push_log

  # Periodo de gracia para que Ignis.stop() termine solo si puede.
  for i in $(seq 1 45); do
    pgrep -f "org.ignis.backend.Main" >/dev/null 2>&1 || { echo "[unblock] driver gone, exit"; push_log; exit 0; }
    sleep 1
  done
  echo "[unblock] grace elapsed, forcing socket close"
  push_log

  JAVA_PIDS=$(pgrep -f "org.ignis.backend.Main" || true)
  [ -z "$JAVA_PIDS" ] && JAVA_PIDS=$(pgrep -f "java.*ignis" || true)
  echo "[unblock] java pids: ${JAVA_PIDS:-none}"
  [ -z "$JAVA_PIDS" ] && exit 0

  for PID in $JAVA_PIDS; do
    echo "[unblock] PID=$PID"
    SOCKET_INODES=$(awk -v pat="/ignis/dfs/payload/${JOB_ID}/sockets/" '$NF ~ pat {print $7}' "/proc/$PID/net/unix" 2>/dev/null || true)
    echo "[unblock] inodes: ${SOCKET_INODES:-none}"
    [ -z "$SOCKET_INODES" ] && continue

    TARGET_FDS=""
    for fd in /proc/$PID/fd/*; do
      LINK=$(readlink "$fd" 2>/dev/null || true)
      case "$LINK" in
        socket:\[*\])
          INODE=${LINK#socket:[}; INODE=${INODE%]}
          for SI in $SOCKET_INODES; do
            [ "$INODE" = "$SI" ] && TARGET_FDS="$TARGET_FDS $(basename "$fd")"
          done ;;
      esac
    done
    echo "[unblock] target fds: ${TARGET_FDS:-none}"
    [ -z "$TARGET_FDS" ] && continue

    GDB_SCRIPT_HOST=/tmp/unblock-gdb-$PID.gdb
    {
      echo "set pagination off"
      echo "set confirm off"
      echo "set unwindonsignal on"
      echo "set scheduler-locking on"
      for fd in $TARGET_FDS; do
        echo "call (int) shutdown((int)$fd, (int)2)"
      done
      echo "detach"
      echo "quit"
    } > "$GDB_SCRIPT_HOST"

    
    CONT_ID=$(docker ps --filter "ancestor=${IMAGE}" --format '{{.ID}}' | head -1)
    if [ -z "$CONT_ID" ]; then
      CONT_ID=$(docker ps --filter "name=ignis" --format '{{.ID}}' | head -1)
    fi
    if [ -z "$CONT_ID" ]; then
      CONT_ID=$(docker ps --format '{{.ID}} {{.Command}}' | grep -i java | awk '{print $1}' | head -1)
    fi
    echo "[unblock] driver container: ${CONT_ID:-none}"
    if [ -z "$CONT_ID" ]; then
      echo "[unblock] cannot find driver container, skipping gdb"
      continue
    fi

    # Instalar gdb dentro del container si no está
    if ! docker exec "$CONT_ID" which gdb >/dev/null 2>&1; then
      echo "[unblock] installing gdb inside container"
      docker exec "$CONT_ID" sh -c 'apt-get update -qq && apt-get install -y -qq gdb' >/dev/null 2>&1 \
        || docker exec "$CONT_ID" sh -c 'dnf install -y -q gdb' >/dev/null 2>&1 \
        || docker exec "$CONT_ID" sh -c 'yum install -y -q gdb' >/dev/null 2>&1 \
        || echo "[unblock] WARNING: could not install gdb in container"
    fi
    docker exec "$CONT_ID" which gdb || { echo "[unblock] gdb missing in container, abort"; continue; }

    # Mapear host-PID a PID dentro del namespace del container. Usamos NSpid
    # de /proc/PID/status: la segunda columna es el PID en el namespace hijo.
    CONT_PID=$(awk '/^NSpid:/ {print $NF}' /proc/$PID/status 2>/dev/null)
    echo "[unblock] container PID for host PID=$PID is $CONT_PID"
    [ -z "$CONT_PID" ] && { echo "[unblock] cannot resolve container PID, abort"; continue; }

    # Copiar el script de gdb al container y ejecutarlo allí
    docker cp "$GDB_SCRIPT_HOST" "$CONT_ID:/tmp/unblock-gdb.gdb"
    echo "[unblock] running gdb inside container on PID=$CONT_PID"
    docker exec "$CONT_ID" timeout 30 gdb -batch -p "$CONT_PID" -x /tmp/unblock-gdb.gdb 2>&1 \
      || echo "[unblock] gdb nz/timeout"
    push_log
  done
  echo "[unblock] done at $(date -u +%FT%TZ)"
  push_log
) &
UNBLOCK_WATCHER_PID=$!
echo "[user-data] unblock watcher pid=$UNBLOCK_WATCHER_PID"

set +e

docker run --rm \
  --network host \
  -e IGNIS_SCHEDULER_NAME=Cloud \
  -e IGNIS_SCHEDULER_URL=cloud://aws \
  -e IGNIS_SCHEDULER_ENV_JOB="$JOB_ID" \
  -e IGNIS_SCHEDULER_ENV_CONTAINER="$IID" \
  -e IGNIS_HOME=/opt/ignis \
  -e IGNIS_WDIR="/ignis/dfs/payload" \
  -e IGNIS_JOBS_BUCKET="$BUCKET" \
  -e IGNIS_SUBNET_ID="$IGNIS_SUBNET_ID" \
  -e IGNIS_SG_ID="$IGNIS_SG_ID" \
  -e IGNIS_AMI="$IGNIS_AMI" \
  -e IGNIS_INSTANCE_TYPE="$IGNIS_INSTANCE_TYPE" \
  -e EFS_MOUNT_IP="$EFS_MOUNT_IP" \
  -e IGNIS_AWS_REGION="$REGION" \
  -e IGNIS_CLOUD_RUNTIME=true \
  -e IGNIS_IAM_INSTANCE_PROFILE="${IGNIS_IAM_INSTANCE_PROFILE:-LabRole}" \
  -v /ignis/dfs:/ignis/dfs \
  -v /var/tmp/ignis-cloud:/var/tmp/ignis-cloud \
  -v /var/run/docker.sock:/var/run/docker.sock \
  -v /usr/bin/docker:/usr/bin/docker \
  "$IMAGE" /bin/bash -lc '
    set -e

    mkdir -p /var/tmp/ignis/jobs
    ln -sf /var/tmp/ignis-cloud/jobs/'"$JOB_ID"'.json /var/tmp/ignis/jobs/'"$JOB_ID"'.json
    chmod -R 777 /var/tmp/ignis
    find /ignis/dfs -not -path '*/ssh/*' -exec chmod 777 {} \; 2>/dev/null || true
    chmod 777 /tmp
 
    echo "[container] environment:"
    env | grep -E "^IGNIS|^EFS|^FI_" | sort

    echo "[container] payload:"
    find /ignis/dfs/payload -maxdepth 2 | sort || true

    echo "[container] launching driver via ignis-job..."
    echo "[container] CMD: '"$CMD"'"
 
    set +e
    '"$CMD"' > /var/tmp/ignis-cloud/driver.log 2>&1
    DRIVER_RC=$?
    set -e
 
    echo "[container] driver exited with rc=$DRIVER_RC"
    cat /var/tmp/ignis-cloud/driver.log || true
    exit "$DRIVER_RC"
  ' > /tmp/out.txt 2>&1

rc=$?
set -e
echo "[user-data] docker run exited with rc=$rc"

# ── Esperar a que TODOS los executors terminen ───────────────────────────────
EXEC_PREFIX="jobs/${JOB_ID}/executors"
TOTAL_KEY="s3://${BUCKET}/${EXEC_PREFIX}/total.json"
FIRST_WAIT_TIMEOUT=300      # 5 min para que aparezca total.json
BARRIER_TIMEOUT=1800        # 30 min para que todos terminen
WAIT_INTERVAL=15
WAITED=0
TOTAL=""

echo "[user-data] phase 1: waiting for total.json"
while [ "$WAITED" -lt "$FIRST_WAIT_TIMEOUT" ]; do
  if aws --region "$REGION" s3 cp "$TOTAL_KEY" /tmp/total.json >/dev/null 2>&1; then
    TOTAL=$(grep -o '"total":[0-9]*' /tmp/total.json | grep -o '[0-9]*')
    if [ -n "$TOTAL" ]; then
      echo "[user-data] expecting $TOTAL executors"
      break
    fi
  fi
  sleep "$WAIT_INTERVAL"
  WAITED=$((WAITED + WAIT_INTERVAL))
done

if [ -z "$TOTAL" ]; then
  echo "[user-data] WARNING: total.json never appeared after ${FIRST_WAIT_TIMEOUT}s; skipping barrier"
else
  echo "[user-data] phase 2: waiting for $TOTAL done.json markers"
  WAITED=0
  while [ "$WAITED" -lt "$BARRIER_TIMEOUT" ]; do
    DONE_COUNT=$(aws --region "$REGION" s3 ls "s3://${BUCKET}/${EXEC_PREFIX}/" --recursive 2>/dev/null \
      | grep -c '/done\.json$' || true)
    echo "[user-data] executors done=$DONE_COUNT / $TOTAL (waited ${WAITED}s)"
    if [ "$DONE_COUNT" -ge "$TOTAL" ]; then
      echo "[user-data] all $TOTAL executors finished"
      break
    fi
    sleep "$WAIT_INTERVAL"
    WAITED=$((WAITED + WAIT_INTERVAL))
  done
  if [ "$DONE_COUNT" -lt "$TOTAL" ]; then
    echo "[user-data] WARNING: barrier timeout after ${BARRIER_TIMEOUT}s; done=$DONE_COUNT/$TOTAL, proceeding anyway"
  fi
fi

# ── Subir los resultados a S3 ───────────────────────────────────────────
END_TS=$(date -u +"%Y-%m-%dT%H:%M:%SZ" 2>/dev/null || echo "")
STATE="FAILED"
[ "$rc" -eq 0 ] && STATE="FINISHED"
 
aws --region "$REGION" s3 cp /tmp/out.txt \
  "s3://$BUCKET/jobs/$JOB_ID/out.txt" || true
 
if [ -f /var/tmp/ignis-cloud/driver.log ]; then
  aws --region "$REGION" s3 cp /var/tmp/ignis-cloud/driver.log \
    "s3://$BUCKET/jobs/$JOB_ID/driver.log" || true
fi
 
# Upload results from EFS output dir and any job subdirectory.
if [ -d "/ignis/dfs/output" ]; then
  aws --region "$REGION" s3 sync "/ignis/dfs/output" \
    "s3://$BUCKET/jobs/$JOB_ID/results/" --quiet || true
fi
 
# ── Subir las carpetas de /ignis/dfs/payload/ a /results de S3 ───────────────
echo "[user-data] uploading payload subdirectories to results/"
ls -la /ignis/dfs/payload/ || true

if [ -d "/ignis/dfs/payload" ]; then
  find /ignis/dfs/payload/ -mindepth 1 -maxdepth 1 -type d 2>/dev/null | while read -r dir; do
    dirname=$(basename "$dir")
    echo "[user-data] syncing $dir -> s3://$BUCKET/jobs/$JOB_ID/results/$dirname/"
    aws --region "$REGION" s3 sync "$dir/" \
      "s3://$BUCKET/jobs/$JOB_ID/results/$dirname/" --only-show-errors || true
  done
else
  echo "[user-data] WARNING: /ignis/dfs/payload does not exist"
fi
 
printf '{"state":"%s","rc":%s,"start":"%s","end":"%s"}\n' \
  "$STATE" "$rc" "$START_TS" "$END_TS" \
  | aws --region "$REGION" s3 cp - "s3://$BUCKET/jobs/$JOB_ID/status.json" || true

# Subir el log del watcher de desbloqueo para diagnóstico
if [ -f "$DRIVER_UNBLOCK_LOG" ]; then
  aws --region "$REGION" s3 cp "$DRIVER_UNBLOCK_LOG" \
    "s3://$BUCKET/jobs/$JOB_ID/driver-unblock.log" || true
fi

# Si el watcher sigue vivo, terminarlo
if kill -0 "$UNBLOCK_WATCHER_PID" 2>/dev/null; then
  echo "[user-data] terminating unblock watcher pid=$UNBLOCK_WATCHER_PID"
  kill "$UNBLOCK_WATCHER_PID" 2>/dev/null || true
fi

printf '{"finished":true,"rc":%s,"state":"%s"}\n' "$rc" "$STATE" \
  | aws --region "$REGION" s3 cp - "s3://$BUCKET/jobs/$JOB_ID/driver-finished.json" \
  || echo "[user-data] WARNING: failed to upload driver-finished.json"

echo "[user-data] cleanup complete, shutting down"
shutdown -h now