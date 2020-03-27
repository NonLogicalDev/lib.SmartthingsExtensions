package main

import (
	"encoding/json"
	"fmt"
	"io/ioutil"
	"net/http"
	"os"
)

const _port = 8888

type LogOutput struct {
	Method string      `json:"method"`
	URL    string      `json:"url"`
	Data   interface{} `json:"data"`
}

func handle(writer http.ResponseWriter, request *http.Request) {
	body, err := ioutil.ReadAll(request.Body)
	if err != nil {
		fmt.Fprintln(os.Stderr, "Error: While reading requiest: %v", err.Error())
	}
	writer.WriteHeader(200)

	out := LogOutput{
		Method: request.Method,
		URL:    request.URL.String(),
		Data:   string(body),
	}

	// Try parsing Body as JSON.
	var parsedBody interface{}
	if  err := json.Unmarshal(body, &parsedBody); err == nil {
		out.Data = parsedBody
	}

	jsonOut, _ := json.Marshal(out)
	fmt.Println(string(jsonOut))
}

func main() {
	http.HandleFunc("/", handle)

	fmt.Fprintln(os.Stderr, "Starting the server on port", _port)
	err := http.ListenAndServe(fmt.Sprintf("0.0.0.0:%d", _port), nil)
	if err != nil {
		panic(err)
	}
}
