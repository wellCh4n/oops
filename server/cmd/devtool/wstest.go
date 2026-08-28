package main

import (
	"fmt"
	"os"
	"time"

	"github.com/gorilla/websocket"
)

// wsProbe dials a WebSocket URL, optionally sends an initial payload, and
// prints the first few frames — the verification driver for the log/terminal
// handlers.
func wsProbe(url, send string, frames int) {
	connection, _, err := websocket.DefaultDialer.Dial(url, nil)
	if err != nil {
		fmt.Println("DIAL_ERR:", err)
		os.Exit(1)
	}
	defer connection.Close()
	if send != "" {
		_ = connection.WriteMessage(websocket.TextMessage, []byte(send))
	}
	_ = connection.SetReadDeadline(time.Now().Add(15 * time.Second))
	for i := 0; i < frames; i++ {
		messageType, payload, err := connection.ReadMessage()
		if err != nil {
			fmt.Println("READ_ERR:", err)
			return
		}
		kind := "TEXT"
		if messageType == websocket.BinaryMessage {
			kind = "BIN"
		}
		if len(payload) > 70 {
			payload = payload[:70]
		}
		fmt.Printf("%s: %q\n", kind, payload)
	}
}
