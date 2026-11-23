#ifndef AIR_SESSION_H
#define AIR_SESSION_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

typedef struct AirSession AirSession;
typedef bool (*AirSend)(void *context, const uint8_t *bytes, size_t count);

// All operations and callbacks run on the same thread.
AirSession *air_session_open(void *context, AirSend send, bool handoff);
void air_session_close(AirSession *session);
void air_session_poll(AirSession *session);
void air_session_receive(AirSession *session, const uint8_t *bytes, size_t count);
void air_session_can_send(AirSession *session);
bool air_session_ready(AirSession *session);
bool air_session_reset_observed(AirSession *session);
int air_session_request(AirSession *session, bool write, const uint8_t *bytes, size_t count);
// 0 while pending, negative on failure, or a packed CoAP response code.
int air_session_response(AirSession *session, uint8_t *bytes, size_t *count);
void air_session_cancel_request(AirSession *session);

#endif
