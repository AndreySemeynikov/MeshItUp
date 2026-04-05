package main

import (
	"encoding/json"
	"log"
	"math/rand"
	"net/http"
	"time"
)

// InventoryHandler обрабатывает запросы к складу.
// version — метка версии (v1, v2), возвращается в каждом ответе.
// faultRate — вероятность ошибки от 0 до 100.
type InventoryHandler struct {
	version   string
	faultRate int
	rng       *rand.Rand
}

func NewInventoryHandler(version string, faultRate int) *InventoryHandler {
	return &InventoryHandler{
		version:   version,
		faultRate: faultRate,
		// Создаём собственный генератор случайных чисел.
		// rand.NewSource с текущим временем — чтобы при каждом запуске
		// последовательность была разной.
		rng: rand.New(rand.NewSource(time.Now().UnixNano())),
	}
}

// InventoryResponse — успешный ответ.
type InventoryResponse struct {
	Version   string          `json:"version"`   // какая версия обработала запрос
	Items     []InventoryItem `json:"items"`     // список товаров на складе
	Timestamp string          `json:"timestamp"` // время ответа
}

// InventoryItem — один товар на складе.
type InventoryItem struct {
	ProductID string `json:"productId"`
	Name      string `json:"name"`
	Stock     int    `json:"stock"`
}

// ErrorResponse — ответ при ошибке.
type ErrorResponse struct {
	Error   string `json:"error"`
	Version string `json:"version"`
}

// GetInventory — основной обработчик.
// Если faultRate > 0, часть запросов будет падать с 500 —
// это нужно для тестирования отката canary.
func (h *InventoryHandler) GetInventory(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "application/json")

	// Fault injection: генерируем число от 0 до 99.
	// Если оно меньше faultRate — имитируем ошибку.
	// Например, при faultRate=20 числа 0..19 дадут ошибку (20% запросов).
	if h.faultRate > 0 && h.rng.Intn(100) < h.faultRate {
		log.Printf("✗ [%s] Injecting fault (rate: %d%%)", h.version, h.faultRate)

		w.WriteHeader(http.StatusInternalServerError)
		json.NewEncoder(w).Encode(ErrorResponse{
			Error:   "internal server error (simulated)",
			Version: h.version,
		})
		return
	}

	// Нормальный ответ — список товаров на складе.
	// Данные захардкожены, потому что это тестовый сервис.
	// В реальном приложении здесь был бы запрос в базу данных.
	response := InventoryResponse{
		Version:   h.version,
		Timestamp: time.Now().UTC().Format(time.RFC3339),
		Items: []InventoryItem{
			{ProductID: "prod-001", Name: "Laptop", Stock: 42},
			{ProductID: "prod-002", Name: "Mouse", Stock: 156},
			{ProductID: "prod-003", Name: "Keyboard", Stock: 89},
		},
	}

	log.Printf("✓ [%s] NEW VERSION Returning inventory (%d items)", h.version, len(response.Items))

	w.WriteHeader(http.StatusOK)
	json.NewEncoder(w).Encode(response)
}

// HealthCheck — проверка здоровья.
// Всегда возвращает 200, даже если fault injection включён.
// Это правильно: сервис жив и работает, просто часть запросов
// намеренно возвращает ошибки для тестирования.
func (h *InventoryHandler) HealthCheck(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusOK)
	json.NewEncoder(w).Encode(map[string]string{
		"status":  "healthy",
		"service": "inventory-service",
		"version": h.version,
	})
}
