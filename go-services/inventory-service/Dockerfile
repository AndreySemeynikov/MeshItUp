FROM golang:1.22-alpine AS builder
WORKDIR /app
COPY go.mod ./
RUN go mod download
COPY . .
RUN CGO_ENABLED=0 GOOS=linux go build -o inventory-service .

FROM alpine:3.19
WORKDIR /app
COPY --from=builder /app/inventory-service .
EXPOSE 8082
CMD ["./inventory-service"]