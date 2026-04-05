package main

import (
	"log"
	"net/http"
	"os"
	"strconv"
)

func main() {
	port := os.Getenv("PORT")
	if port == "" {
		port = "8082"
	}

	// VERSION — метка версии сервиса.
	// Будет возвращаться в каждом ответе, чтобы при canary
	// было видно, какая именно версия обработала запрос.
	version := os.Getenv("VERSION")
	if version == "" {
		version = "v1"
	}

	// FAULT_RATE — процент запросов, на которые сервис ответит ошибкой 500.
	// Для стабильной версии (v1) — 0.
	// Для "сломанной" canary-версии — например, 20 (каждый пятый запрос падает).
	// Для "хорошей" canary-версии — 0.
	faultRateStr := os.Getenv("FAULT_RATE")
	faultRate := 0
	if faultRateStr != "" {
		parsed, err := strconv.Atoi(faultRateStr)
		if err != nil {
			log.Fatalf("Invalid FAULT_RATE value: %s", faultRateStr)
		}
		faultRate = parsed
	}

	handler := NewInventoryHandler(version, faultRate)

	mux := http.NewServeMux()

	// Основной эндпоинт — возвращает данные о складских остатках.
	mux.HandleFunc("/api/inventory", handler.GetInventory)

	// Health check для Kubernetes.
	mux.HandleFunc("/health", handler.HealthCheck)

	log.Printf("Inventory Service %s starting on port %s (fault rate: %d%%)", version, port, faultRate)

	if err := http.ListenAndServe(":"+port, mux); err != nil {
		log.Fatalf("Failed to start server: %v", err)
	}
}
