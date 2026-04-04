# OCI Project Tenant Provisioning Guide

This guide adds a small operator script for the data server so you can provision a new project tenant with one command.

What the script creates:

1. A MariaDB database dedicated to the project
2. A MariaDB user with privileges on that database only
3. A MinIO bucket dedicated to the project
4. A MinIO user that uses the same login and password as the MariaDB user
5. A MinIO bucket-scoped policy attached to that user

File:

- `/C:/Users/kjs99/Desktop/calen/deploy/oci/scripts/provision-project-tenant.sh`

## When to use it

Use this on the data server when you want to attach another application to the same MariaDB + MinIO server, while keeping each project isolated by:

- its own MariaDB database
- its own MariaDB user
- its own MinIO bucket
- its own MinIO access key and secret key

## Inputs

The script takes:

1. `project_slug`
2. `login_id`
3. `password`
4. optional `db_name`
5. optional `bucket_name`

By default:

- the MariaDB database name is derived from `project_slug`
- the MinIO bucket name is derived from `project_slug`
- the MariaDB and MinIO login/password are the same values you pass in

## Example

If you want to create a new tenant for `fileinnout` and use the same ID/password for both MariaDB and MinIO:

```bash
cd ~/calen
chmod +x deploy/oci/scripts/provision-project-tenant.sh
./deploy/oci/scripts/provision-project-tenant.sh fileinnout testid1234 testpw1234
```

The script will then:

- create a MariaDB database such as `fileinnout`
- create or update MariaDB user `testid1234`
- create a MinIO bucket such as `fileinnout`
- create or recreate MinIO user `testid1234`
- attach a bucket-only MinIO policy to that user

## Requirements

Run the script on the data server where these files already exist:

- `/C:/Users/kjs99/Desktop/calen/docker-compose.oci.data.yml`
- `/C:/Users/kjs99/Desktop/calen/.env.oci.data`

The data stack must already be up:

```bash
cd ~/calen
docker compose --env-file .env.oci.data -f docker-compose.oci.data.yml up -d
```

The script reads the shared admin credentials from `.env.oci.data`:

- `DB_ROOT_PASSWORD`
- `MINIO_ROOT_USER`
- `MINIO_ROOT_PASSWORD`
- `MINIO_API_INTERNAL_URL`

## Output values to copy into the new project

After the script finishes, it prints the values to use in the new project's application environment:

```env
DB_NAME=<project db name>
DB_USER=<login_id>
DB_PASSWORD=<password>
MINIO_ROOT_USER=<login_id>
MINIO_ROOT_PASSWORD=<password>
MINIO_CLOUD_BUCKET=<bucket name>
MINIO_API_INTERNAL_URL=<data server internal minio url>
MINIO_PUBLIC_API=<public minio url>
```

## Notes

- `login_id` must use only letters, numbers, dot, underscore, or hyphen.
- `password` must be at least 8 characters and must not contain spaces or a single quote.
- Re-running the script for the same project recreates the MinIO user and policy so the final state stays consistent with the latest input.
- The script grants MariaDB privileges only on the generated database, not on every database.
- The MinIO policy is bucket-scoped, not server-wide.

## Example with explicit names

If you want names that differ from the project slug:

```bash
cd ~/calen
./deploy/oci/scripts/provision-project-tenant.sh fileinnout testid1234 testpw1234 fileinnout_db fileinnout-assets
```

That would create:

- database: `fileinnout_db`
- bucket: `fileinnout-assets`
- login: `testid1234`
- password: `testpw1234`
