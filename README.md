# AgriPulse

AgriPulse is an AI-powered Spring Boot application for agricultural supply-chain risk analysis. A user enters a crop and region, Groq estimates the likely risk level, AgriPulse stores the result in PostgreSQL, and a Java alert tool is triggered automatically when the model classifies the case as `High`.

## Free-Forever Deployment Strategy

This repository is configured for a student-friendly deployment path with no credit card required:

- `Backend hosting`: Render Free Web Service
- `Database`: Neon or Supabase Postgres, not the hosting provider database
- `Storage`: Supabase Storage or Cloudinary for any future uploads
- `Monitoring`: UptimeRobot free plan, with Better Stack as an optional alternative

I chose Render in this repo because its official free docs still support Docker-based free web services, but they spin down after 15 minutes of inactivity and take about a minute to wake up again. That behavior is documented by Render and is why AgriPulse is tuned for cold starts and sleeping databases.

## System Architecture

- `Thymeleaf + Tailwind UI`: server-rendered dashboard with a search form, loading spinner, risk chart, and paginated history.
- `REST API`: `POST /api/risk/analyze` for analysis and `GET /api/risk/history` for paginated report history.
- `Service layer`: builds prompts, calls Groq through Spring AI, normalizes the answer, and saves it.
- `PostgreSQL`: local H2 in `dev`, external PostgreSQL in `prod`.
- `Actuator`: liveness and readiness endpoints for cloud deployment and monitoring.

## Cold-Start and Sleep-Friendly Settings

AgriPulse is configured to behave better on free hosting and serverless Postgres:

- Render blueprint uses `plan: free`.
- Deployment health checks use `/actuator/health/liveness` instead of a heavier database-dependent check.
- `server.port=${PORT:8080}` makes the app compatible with free hosts that inject a dynamic port.
- HikariCP in production uses:
  - `maximum-pool-size=5`
  - `minimum-idle=0`
  - `connection-timeout=30000`
  - `validation-timeout=5000`
  - `initialization-fail-timeout=0`
  - `connection-test-query=SELECT 1`
- The datasource parser accepts both:
  - `jdbc:postgresql://...`
  - `postgresql://...` or `postgres://...`-style external URLs with query parameters such as `sslmode=require`

These settings reduce the chance of 500 errors when Neon or Supabase is asleep and waking up.

## Profiles

- `dev`: uses in-memory H2 and enables the H2 console.
- `prod`: uses external PostgreSQL from environment variables.

## Environment Variables

Required:

- `GROQ_API_KEY`
- `SPRING_PROFILES_ACTIVE`
- `DATABASE_URL`

Optional:

- `GROQ_MODEL`
- `DATABASE_USERNAME`
- `DATABASE_PASSWORD`

Recommended free-tier default:

- `GROQ_MODEL=llama-3.1-8b-instant`

Example production URL formats:

```text
postgresql://user:password@host:5432/dbname?sslmode=require
```

```text
jdbc:postgresql://host:5432/dbname?sslmode=require
```

## Local Run

1. Install Java 17.
2. Use the Maven wrapper already included in this repo.
3. Set your Groq key:

```powershell
$env:GROQ_API_KEY="your-real-key"
```

4. Start the app:

```powershell
.\mvnw.cmd spring-boot:run
```

5. Open:

- App: `http://localhost:8080`
- H2 console: `http://localhost:8080/h2-console`

## Docker Run

Build the image:

```powershell
docker build -t agripulse .
```

Run locally in development mode:

```powershell
docker run --rm -p 8080:8080 `
  -e SPRING_PROFILES_ACTIVE=dev `
  -e GROQ_API_KEY=your-real-key `
  agripulse
```

Run in production mode with an external PostgreSQL database:

```powershell
docker run --rm -p 8080:8080 `
  -e SPRING_PROFILES_ACTIVE=prod `
  -e GROQ_API_KEY=your-real-key `
  -e DATABASE_URL="postgresql://user:password@host:5432/agripulse?sslmode=require" `
  agripulse
```

## Free Database Options

### Neon

Use Neon when you want serverless Postgres that can scale to zero. AgriPulse accepts Neon-style connection strings directly through `DATABASE_URL`.

### Supabase

Use Supabase when you want free Postgres plus free object storage in the same project. Supabase’s current docs list a free project database size of 500 MB and free storage of 1 GB.

## File Storage Guidance

AgriPulse does not currently upload files, but if you add images or PDFs later:

- do not store them on local disk
- use `Supabase Storage` if you already chose Supabase for Postgres
- use `Cloudinary` if you want a dedicated media service

The `.env.example` file includes placeholder variables for both options so the project is ready for that future extension.

## Free Hosting Setup

### Render

This repo includes a `render.yaml` configured for a free Docker web service and an external database.

Steps:

1. Create a Render account.
2. Create a new Web Service from `Shaurya1120/agripulse`.
3. Make sure the instance type is `Free`.
4. Set these environment variables:
   - `SPRING_PROFILES_ACTIVE=prod`
   - `GROQ_API_KEY=...`
   - `GROQ_MODEL=llama-3.1-8b-instant`
   - `DATABASE_URL=...`
   - `DATABASE_USERNAME=...` only if your provider gives separate credentials
   - `DATABASE_PASSWORD=...` only if your provider gives separate credentials
5. Keep the health check path as:
   - `/actuator/health/liveness`

Important Render limitations from the official docs:

- free web services spin down after 15 minutes of inactivity
- cold start can take about one minute
- local filesystem changes are lost

### Railway

This repo includes `railway.json` with Dockerfile-based deploy settings and a liveness health check.

Steps:

1. Create a Railway project from `Shaurya1120/agripulse`.
2. Let Railway build from the root `Dockerfile`.
3. Set:
   - `SPRING_PROFILES_ACTIVE=prod`
   - `GROQ_API_KEY=...`
   - `GROQ_MODEL=llama-3.1-8b-instant`
   - `DATABASE_URL=...`
4. Keep the health check path as:
   - `/actuator/health/liveness`

## Monitoring and Anti-Sleep Options

If you want a mostly-always-awake demo, use a free monitor to hit the public URL regularly.

### UptimeRobot

UptimeRobot’s free plan currently offers 5-minute monitoring with no credit card required. Create an HTTP monitor that pings:

```text
https://your-app-url/actuator/health/liveness
```

### Better Stack

Better Stack currently advertises a free personal tier with uptime monitoring, but its free uptime product uses 3-minute checks according to the public uptime page. If you want the simplest no-cost option, UptimeRobot is easier to recommend for student demos.

Note:

- your prompt requested 14-minute checks, but the current public free plans I verified are 5 minutes on UptimeRobot and 3 minutes on Better Stack, not 14 minutes
- frequent pings may keep a free service awake, but platform policies and monthly free limits still apply

## Health Endpoints

AgriPulse exposes:

- `GET /actuator/health`
- `GET /actuator/health/liveness`
- `GET /actuator/health/readiness`

Recommended usage:

- hosting platform deploy checks: `/actuator/health/liveness`
- manual diagnostics: `/actuator/health/readiness`

## Plan B Business Logic

### Turmeric

For turmeric supply chains, Plan B should favor alternate sourcing clusters, pre-booked logistics, and dried inventory buffers. This matters because turmeric can be hit by weather-driven yield stress, storage quality issues, and transport bottlenecks that ripple quickly into pricing.

### Basmati Rice

For basmati rice, Plan B should emphasize supplier diversification, warehouse positioning near export lanes, and close monitoring of weather and policy shocks. That reduces exposure when monsoon disruption, procurement delays, or export restrictions threaten shipment timing.

## Verified Build

This repo has been verified with:

```powershell
.\mvnw.cmd test
```

## Official Sources Used

- [Render free web services](https://render.com/docs/free)
- [Render blueprint spec](https://render.com/docs/blueprint-spec)
- [Neon pricing and plans](https://neon.tech/pricing)
- [Supabase billing and free limits](https://supabase.com/docs/guides/platform/billing-on-supabase)
- [Supabase storage pricing](https://supabase.com/docs/guides/storage/pricing)
- [Railway config as code](https://docs.railway.com/reference/config-as-code)
- [Railway health checks](https://docs.railway.com/reference/healthchecks)
- [UptimeRobot pricing](https://uptimerobot.com/pricing/)
- [Better Stack uptime](https://betterstack.com/uptime)
