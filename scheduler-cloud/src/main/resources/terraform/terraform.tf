# terraform.tf
#------------------------------------------------------------------------------
# Declaración de los requisitos de Terraform y los proveedores empleados
# por el despliegue de la infraestructura de Ignis en AWS.
#------------------------------------------------------------------------------

terraform {
  required_version = ">= 1.2"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.92"
    }
  }
}