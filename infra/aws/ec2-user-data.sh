#!/bin/bash
# =========================================================
# "User data" para las EC2 (Amazon Linux 2023). Se ejecuta UNA vez al crear la instancia.
# EC2 -> Launch instance -> Advanced details -> User data -> pegar este contenido.
# Instala Docker, el plugin Compose, Buildx y Git.
# =========================================================
set -euxo pipefail

dnf update -y
dnf install -y docker git
systemctl enable --now docker
usermod -aG docker ec2-user   # ec2-user puede usar docker sin sudo (tras reconectar SSH)

PLUGINS=/usr/local/lib/docker/cli-plugins
mkdir -p "$PLUGINS"

# Docker Compose v2 (docker compose ...)
curl -fsSL -o "$PLUGINS/docker-compose" \
  https://github.com/docker/compose/releases/latest/download/docker-compose-linux-x86_64
chmod +x "$PLUGINS/docker-compose"

# Buildx (lo usa "docker compose build" para los Dockerfile con caché de Maven)
BUILDX=$(curl -fsSL https://api.github.com/repos/docker/buildx/releases/latest | grep -o '"tag_name": *"[^"]*"' | cut -d'"' -f4)
curl -fsSL -o "$PLUGINS/docker-buildx" \
  "https://github.com/docker/buildx/releases/download/${BUILDX}/buildx-${BUILDX}.linux-amd64"
chmod +x "$PLUGINS/docker-buildx"

# Swap de 2 GB: ayuda a que 4 JVM + compilación quepan en una t3.medium
fallocate -l 2G /swapfile && chmod 600 /swapfile && mkswap /swapfile && swapon /swapfile
echo '/swapfile swap swap defaults 0 0' >> /etc/fstab

docker --version > /home/ec2-user/docker-listo.txt
docker compose version >> /home/ec2-user/docker-listo.txt
