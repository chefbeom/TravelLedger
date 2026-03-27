# Calen

Full-stack household ledger, travel journal, and family album app built with Spring Boot and Vue.

Uploads are now backend-mediated only:

- Travel media uploads go through the backend
- Family album uploads go through the backend
- GPX uploads go through the backend

The browser does not upload directly to MinIO in the default deployment.

## Stack

- `frontend`: Vue 3 + Vite build served by Nginx
- `backend`: Spring Boot + JPA + Spring Security
- `mariadb`: primary relational database
- `minio`: object storage used by the backend

## Local Docker Compose

1. Copy the environment file.

```powershell
Copy-Item .env.example .env
```

2. Update passwords in `.env`.

3. Start the stack.

```powershell
docker compose up -d --build
```

4. Open the app.

- App: `http://localhost:8080`
- MinIO API: `http://localhost:9000`
- MinIO Console: `http://localhost:9001`

If `APP_SEED_ENABLED=true`, test accounts are created:

- `hana / test1234`
- `minsu / test1234`

## OCI One-VM Deployment With Host Nginx

Use `docker-compose.oci.yml` when you run everything on a single OCI VM and terminate TLS on the host machine.

### Architecture

- Public HTTPS entrypoint: `https://www.innoutdrive.space`
- Host Nginx proxies `/` to `127.0.0.1:8081`
- Host Nginx proxies `/api/` to `127.0.0.1:8080`
- Backend talks to `mariadb` and `minio` on the private Docker network
- Only `80`, `443`, and `22` should be public at the VM firewall level

### 1. Prepare `.env`

```bash
cp .env.example .env
```

Recommended production values:

```env
COMPOSE_PROJECT_NAME=calen
TZ=Asia/Seoul
OCI_FRONTEND_BIND_PORT=8081
OCI_BACKEND_BIND_PORT=8080
OCI_MINIO_CONSOLE_BIND_PORT=9001

DB_NAME=calen
DB_USER=calen
DB_PASSWORD=replace-with-a-strong-password
DB_ROOT_PASSWORD=replace-with-a-strong-root-password

MINIO_ROOT_USER=minioadmin
MINIO_ROOT_PASSWORD=replace-with-a-strong-minio-password
MINIO_CLOUD_BUCKET=budgetjourneybucket
MINIO_API_INTERNAL_URL=http://minio:9000

APP_SEED_ENABLED=false
JWT_KEY=replace-with-a-long-random-string
TRAVEL_EXCHANGE_RATE_BASE_URL=https://api.frankfurter.dev/v1
TRAVEL_EXCHANGE_RATE_CACHE_MINUTES=30
TRAVEL_PRESIGNED_UPLOAD_ENABLED=false
H2_CONSOLE_ENABLED=false
```

### 2. Start the OCI stack

```bash
docker compose -f docker-compose.oci.yml up -d --build
```

The OCI stack binds these services to localhost only:

- Frontend container: `127.0.0.1:8081`
- Backend container: `127.0.0.1:8080`
- MinIO Console: `127.0.0.1:9001`

`mariadb` and the MinIO API are not published externally.

### 3. Install host Nginx

```bash
sudo apt update
sudo apt install -y nginx
```

### 4. Put certificate files on the VM

If your certificate provider gives `certificate.crt`, `ca_bundle.crt`, and `private.key`, create `fullchain.crt` like this:

```bash
cat certificate.crt ca_bundle.crt > fullchain.crt
```

Copy the files to:

- `/etc/nginx/ssl/www.innoutdrive.space/fullchain.crt`
- `/etc/nginx/ssl/www.innoutdrive.space/private.key`

### 5. Install the Nginx site config

Use [www.innoutdrive.space.conf](/C:/Users/kjs99/Desktop/calen/deploy/oci/nginx/www.innoutdrive.space.conf).

```bash
sudo cp deploy/oci/nginx/www.innoutdrive.space.conf /etc/nginx/sites-available/www.innoutdrive.space
sudo ln -sf /etc/nginx/sites-available/www.innoutdrive.space /etc/nginx/sites-enabled/www.innoutdrive.space
sudo rm -f /etc/nginx/sites-enabled/default
```

### 6. Validate and reload Nginx

```bash
sudo nginx -t
sudo systemctl reload nginx
```

### 7. Open the app

- `https://www.innoutdrive.space`

### 8. Logs and status

```bash
docker compose -f docker-compose.oci.yml ps
docker compose -f docker-compose.oci.yml logs -f backend
docker compose -f docker-compose.oci.yml logs -f frontend
```

## Data Volumes

Named volumes used by Docker:

- `mariadb-data`
- `minio-data`
- `backend-uploads`

Remove everything, including data:

```powershell
docker compose down -v
```

For OCI:

```bash
docker compose -f docker-compose.oci.yml down -v
```
