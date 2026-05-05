# AgriPulse

AgriPulse is an AI-powered Spring Boot application for agricultural supply-chain risk analysis. A user enters a crop and region, Gemini estimates the likely risk level, AgriPulse stores the result in the database, and a Java alert tool is triggered automatically when the model classifies the case as `High`.

## System Architecture

- `Thymeleaf + Tailwind UI`: server-rendered dashboard with a search form, loading spinner, risk chart, and paginated history.
- `REST API`: `POST /api/risk/analyze` for analysis and `GET /api/risk/history` for paginated report history.
- `Service Layer`: orchestrates prompt-building, Spring AI tool calling, response normalization, and persistence.
- `Spring AI + Gemini`: structured risk analysis with function calling for `sendEmergencyAlert`.
- `Persistence`: JPA entity stored in H2 for local development and PostgreSQL for production.
- `Actuator`: exposes `/actuator/health` for cloud health checks.

## Profiles

- `dev`: uses in-memory H2 and enables the H2 console.
- `prod`: uses PostgreSQL from environment variables.

## Environment Variables

- `GEMINI_API_KEY`: required for AI analysis.
- `GEMINI_MODEL`: optional, defaults to `gemini-2.0-flash`.
- `DATABASE_URL`: required in production. This project expects a JDBC-style PostgreSQL URL, for example `jdbc:postgresql://host:5432/dbname`.
- `DATABASE_USERNAME`: optional if your platform does not embed credentials in the URL.
- `DATABASE_PASSWORD`: optional if your platform does not embed credentials in the URL.
- `SPRING_PROFILES_ACTIVE`: use `dev` locally or `prod` in deployment.

## Local Run

1. Install Java 17.
2. Install Maven 3.9+.
3. Set your Gemini key:

```powershell
$env:GEMINI_API_KEY="your-real-key"
```

4. Start the app:

```powershell
mvn spring-boot:run
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
  -e GEMINI_API_KEY=your-real-key `
  agripulse
```

Run in production mode with PostgreSQL:

```powershell
docker run --rm -p 8080:8080 `
  -e SPRING_PROFILES_ACTIVE=prod `
  -e GEMINI_API_KEY=your-real-key `
  -e DATABASE_URL=jdbc:postgresql://host:5432/dbname `
  -e DATABASE_USERNAME=postgres `
  -e DATABASE_PASSWORD=secret `
  agripulse
```

## Plan B Business Logic

### Turmeric

For turmeric supply chains, Plan B should favor alternate sourcing clusters, pre-booked logistics, and dried inventory buffers. This matters because turmeric can be hit by weather-driven yield stress, storage quality issues, and transport bottlenecks that ripple quickly into pricing.

### Basmati Rice

For basmati rice, Plan B should emphasize supplier diversification, warehouse positioning near export lanes, and close monitoring of weather and policy shocks. That reduces exposure when monsoon disruption, procurement delays, or export restrictions threaten shipment timing.

## Cloud Deployment Notes

### Render

- Create a new Web Service from your GitHub repository.
- Set the build command to `mvn clean package`.
- Set the start command to `java -jar target/agripulse-0.0.1-SNAPSHOT.jar`.
- Add environment variables for `SPRING_PROFILES_ACTIVE=prod`, `GEMINI_API_KEY`, `DATABASE_URL`, and, if needed, `DATABASE_USERNAME` and `DATABASE_PASSWORD`.
- Point the health check path to `/actuator/health`.

### Railway

- Create a new project from your GitHub repository.
- Add a PostgreSQL service or connect an external one.
- Set `SPRING_PROFILES_ACTIVE=prod` and the same environment variables as above.
- Railway can build from the Dockerfile or directly from Maven.

## Health Check

AgriPulse exposes:

- `GET /actuator/health`

This endpoint is designed for platforms like Render or Railway to detect whether the application is up and ready.
