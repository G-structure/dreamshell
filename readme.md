# Dreamshell

## Setup Commands

Initialize crypto (creates JWT secret):
```
just create-env
```

Initialize local storage (creates required directories):
```
just init-local
```

Build Docker image:
```
just build-docker
```

Create JWT token:
```
just create-jwt
```

## API Usage

Start a New Docker Container:
```
curl -X POST http://localhost:3000/api/start \
  -H "Authorization: Bearer <JWT_TOKEN_STRING>" \
  -H "Content-Type: application/json"
```

Terminate a Docker Container:
```
curl -X POST http://localhost:3000/api/terminate \
  -H "Authorization: Bearer <JWT_TOKEN_STRING>" \
  -H "Content-Type: application/json" \
  -d '{"uuid": "123e4567-e89b-12d3-a456-426614174000"}'
```

## Directory Structure

```
~/.dreamshell/
├── sessions/    # Container session data
└── logs/        # Application logs
```

## Quick Start

1. Initialize the environment:
   ```bash
   just create-env    # Create JWT secret
   just init-local    # Create local directories
   just build-docker  # Build the Docker image
   ```

2. Generate a JWT token:
   ```bash
   just create-jwt
   ```

3. Use the generated token in your API requests.

```
curl -X POST http://localhost:3000/api/start \
  -H "Authorization: Bearer <JWT_TOKEN_STRING>" \
  -H "Content-Type: application/json"
```

4. Connect to the websocket:
```
wscat -c ws://localhost:3000/ws?uuid=123e4567-e89b-12d3-a456-426614174000
```

5.
