output "vpc_id" {
  value = aws_vpc.ignis_vpc.id
}

output "subnet_id" {
  value = aws_subnet.ignis_subnet.id
}

output "sg_id" {
  value = aws_security_group.ignis_sg.id
}

output "jobs_bucket_name" {
  value = aws_s3_bucket.ignis_jobs.bucket
}

output "efs_dns_name" {
  value = aws_efs_file_system.ignis_efs.dns_name
}

output "efs_id" {
  value = aws_efs_file_system.ignis_efs.id
}

output "efs_mount_target_ip" {
  value = aws_efs_mount_target.ignis_efs_mt.ip_address
}