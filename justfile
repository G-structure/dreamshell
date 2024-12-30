# justfile

# Create .env file with a random JWT secret
create-env:
    echo "JWT_SECRET=$(openssl rand -base64 32)" > .env
    echo ".env file created with random JWT secret"

# Build and load Docker image
build-docker:
    cd docker && \
    nix build .#dockerImage && \
    docker load < result

# Initialize local storage directories
init-local:
    mkdir -p ~/.dreamshell/sessions/
    mkdir -p ~/.dreamshell/logs/
    echo "Local storage directories created at ~/.dreamshell/"

# Generate JWT token
create-jwt:
    bb ./scripts/generate-jwt.bb

play:
    bb ./scripts/run-server.bb
