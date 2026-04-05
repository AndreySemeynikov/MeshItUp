package main

import (
	"log"
	"net/http"
	"os"
)

func main() {
	// Порт, на котором слушает gateway.
	// Берём из переменной окружения, чтобы потом в K8s было удобно менять.
	// По умолчанию 8080.
	port := os.Getenv("PORT")
	if port == "" {
		port = "8080"
	}

	// Адрес Order Service, куда gateway пересылает запросы.
	// В обычном режиме — напрямую (http://order-service:8080).
	// Когда появится sidecar proxy — будет http://localhost:15001.
	orderServiceURL := os.Getenv("INVENTORY_SERVICE_URL")
	if orderServiceURL == "" {
		orderServiceURL = "http://localhost:8081"
	}

	// Создаём наш обработчик, передавая ему адрес downstream-сервиса.
	handler := NewGatewayHandler(orderServiceURL)

	// Регистрируем маршруты.
	mux := http.NewServeMux()

	// Основной маршрут — все запросы на /api/ пересылаются в Order Service.
	mux.HandleFunc("/api/", handler.ProxyToOrderService)

	// Health check — Kubernetes будет дёргать этот endpoint,
	// чтобы понять, жив ли pod.
	mux.HandleFunc("/health", handler.HealthCheck)

	log.Printf("API Gateway starting on port %s", port)
	log.Printf("Forwarding /api/* requests to %s", orderServiceURL)

	// Запускаем HTTP-сервер. Если что-то пошло не так — падаем с ошибкой.
	if err := http.ListenAndServe(":"+port, mux); err != nil {
		log.Fatalf("Failed to start server: %v", err)
	}
}
