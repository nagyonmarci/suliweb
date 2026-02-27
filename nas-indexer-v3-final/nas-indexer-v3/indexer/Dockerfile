FROM golang:1.22-alpine AS builder
RUN apk add --no-cache gcc musl-dev sqlite-dev
WORKDIR /app
COPY go.mod go.sum ./
RUN go mod download
COPY . .
RUN CGO_ENABLED=1 GOOS=linux go build -o indexer .

FROM alpine:3.19
RUN apk add --no-cache sqlite-libs ca-certificates
COPY --from=builder /app/indexer /indexer
CMD ["/indexer"]