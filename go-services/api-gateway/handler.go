package main

import (
	"fmt"
	"io"
	"log"
	"net/http"
	"time"
)

type GatewayHandler struct {
	orderServiceURL string
	client          *http.Client
}

func NewGatewayHandler(orderServiceURL string) *GatewayHandler {
	return &GatewayHandler{
		orderServiceURL: orderServiceURL,
		client: &http.Client{
			Timeout: 10 * time.Second,
		},
	}
}

func (g *GatewayHandler) ProxyToOrderService(w http.ResponseWriter, r *http.Request) {
	// Идём через свой sidecar на localhost:15001
	// Sidecar знает куда проксировать по заголовку X-Mesh-Destination
	targetURL := "http://localhost:15001" + r.URL.Path
	// прямой путь, минуя сайдкар
	//targetURL := "http://localhost:8082" + r.URL.Path

	if r.URL.RawQuery != "" {
		targetURL += "?" + r.URL.RawQuery
	}

	log.Printf("→ Forwarding %s %s via sidecar to %s", r.Method, r.URL.Path, targetURL)

	proxyReq, err := http.NewRequestWithContext(r.Context(), r.Method, targetURL, r.Body)
	if err != nil {
		log.Printf("✗ Failed to create request: %v", err)
		http.Error(w, `{"error": "failed to create proxy request"}`, http.StatusInternalServerError)
		return
	}

	// Копируем заголовки из оригинального запроса
	for key, values := range r.Header {
		for _, value := range values {
			proxyReq.Header.Add(key, value)
		}
	}

	// Говорим sidecar куда проксировать
	proxyReq.Header.Set("X-Mesh-Destination", "order-service")
	proxyReq.Header.Set("X-Forwarded-By", "api-gateway")

	resp, err := g.client.Do(proxyReq)
	if err != nil {
		log.Printf("✗ Request to Order Service failed: %v", err)
		http.Error(w, fmt.Sprintf(`{"error": "upstream request failed: %v"}`, err), http.StatusBadGateway)
		return
	}
	defer resp.Body.Close()

	body, err := io.ReadAll(resp.Body)
	if err != nil {
		log.Printf("✗ Failed to read response body: %v", err)
		http.Error(w, `{"error": "failed to read upstream response"}`, http.StatusInternalServerError)
		return
	}

	log.Printf("← Response from Order Service: %d (%d bytes)", resp.StatusCode, len(body))

	for key, values := range resp.Header {
		for _, value := range values {
			w.Header().Add(key, value)
		}
	}

	w.WriteHeader(resp.StatusCode)
	w.Write(body)
}

func (g *GatewayHandler) HealthCheck(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusOK)
	w.Write([]byte(`{"status": "healthy", "service": "api-gateway"}`))
}
