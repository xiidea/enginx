package api

import "time"

func nowForTest() time.Time {
	return time.Date(2026, 8, 28, 12, 0, 0, 0, time.UTC)
}
