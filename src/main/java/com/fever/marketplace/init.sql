-- Database initialization script for Docker
-- This ensures the database is ready for Flyway migrations

-- Create the database if it doesn't exist (PostgreSQL doesn't support IF NOT EXISTS for databases)
-- The database is already created by the POSTGRES_DB environment variable

-- Create user if not exists (PostgreSQL 9.1+)
DO
$$
BEGIN
   IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'fever') THEN
      CREATE USER fever WITH PASSWORD 'feverpass';
END IF;
END
$$;

-- Grant privileges
GRANT ALL PRIVILEGES ON DATABASE fever_marketplace TO fever;

-- Set timezone
SET timezone = 'UTC';
