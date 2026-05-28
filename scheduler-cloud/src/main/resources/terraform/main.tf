provider "aws" {
  region = var.aws_region
}

# Sufijo aleatorio empleado para garantizar la unicidad de los nombres de los recursos (S3, EFS, etc.)
resource "random_string" "bucket_suffix" {
  length  = 8
  special = false
  upper   = false
}

# ------------------------------------------------------------------------------
# Red
# ------------------------------------------------------------------------------

// VPC
resource "aws_vpc" "ignis_vpc" {
  cidr_block           = "10.0.0.0/16"
  enable_dns_hostnames = true
  enable_dns_support   = true

  tags = {
    Name = "ignis_vpc"
  }
}

// Subnet
resource "aws_subnet" "ignis_subnet" {
  vpc_id                  = aws_vpc.ignis_vpc.id
  cidr_block              = "10.0.1.0/24"
  map_public_ip_on_launch = true
  availability_zone       = var.availability_zone

  tags = {
    Name = "ignis_subnet"
  }
}

// Internet Gateway
resource "aws_internet_gateway" "ignis_igw" {
    vpc_id = aws_vpc.ignis_vpc.id

    tags = {
        Name = "ignis-igw"
    }
}

// Route Table
resource "aws_route_table" "ignis_route_table" {
  vpc_id = aws_vpc.ignis_vpc.id

  route {
    cidr_block = "0.0.0.0/0"
    gateway_id = aws_internet_gateway.ignis_igw.id
  }

  tags = {
    Name = "ignis-route-table"
  }
}

// Route Table Association
resource "aws_route_table_association" "ignis_route_assoc" {
  subnet_id      = aws_subnet.ignis_subnet.id
  route_table_id = aws_route_table.ignis_route_table.id
}

# ------------------------------------------------------------------------------
# Seguridad
# ------------------------------------------------------------------------------
resource "aws_security_group" "ignis_sg" {
  vpc_id      = aws_vpc.ignis_vpc.id
  name        = "ignis-sg"
  description = "Security group for Ignis scheduler instances"

  # SSH desde el exterior
  ingress {
    from_port   = 22
    to_port     = 22
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  # Comunicación intra-cluster
  ingress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    self        = true
  }

  # Salida sin restricciones
  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = {
    Name = "ignis-sg"
  }
}

# ------------------------------------------------------------------------------
# Almacenamiento: S3
# ------------------------------------------------------------------------------

// S3
resource "aws_s3_bucket" "ignis_jobs" {
  bucket = "ignis-jobs-${random_string.bucket_suffix.result}"

  tags = {
    Name = "ignis-jobs-bucket"
  }
}

// S3 Block
resource "aws_s3_bucket_public_access_block" "ignis_jobs" {
  bucket = aws_s3_bucket.ignis_jobs.id

  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

# ------------------------------------------------------------------------------
# Almacenamiento: EFS
# ------------------------------------------------------------------------------

// EFS
resource "aws_efs_file_system" "ignis_efs" {
  creation_token   = "ignis-efs-${random_string.bucket_suffix.result}"
  performance_mode = "generalPurpose"
  throughput_mode  = "bursting"

  # En entornos reales de producción debe ser true
  # Por simplicidad y para evitar costes adicionales, queda deshabilitado
  encrypted = false

  tags = { 
    Name = "ignis-efs" 
  }
}

resource "aws_efs_mount_target" "ignis_efs_mt" {
  file_system_id  = aws_efs_file_system.ignis_efs.id
  subnet_id       = aws_subnet.ignis_subnet.id
  security_groups = [aws_security_group.ignis_sg.id]
}