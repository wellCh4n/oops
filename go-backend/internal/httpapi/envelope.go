package httpapi

// Result mirrors the Java Result<T> record: {success, message, data}.
// Message is a pointer so a success renders "message":null exactly like
// Jackson serializes the Java record.
type Result struct {
	Success bool    `json:"success"`
	Message *string `json:"message"`
	Data    any     `json:"data"`
}

// Page mirrors the Java Page<T> record: {total, data, size, totalPages}.
type Page struct {
	Total      int64 `json:"total"`
	Data       any   `json:"data"`
	Size       int   `json:"size"`
	TotalPages int   `json:"totalPages"`
}

func NewPage(total int64, data any, size int) Page {
	totalPages := 0
	if size > 0 {
		totalPages = int((total + int64(size) - 1) / int64(size))
	}
	return Page{Total: total, Data: data, Size: size, TotalPages: totalPages}
}

func ok(data any) Result         { return Result{Success: true, Data: data} }
func fail(message string) Result { return Result{Success: false, Message: &message} }
