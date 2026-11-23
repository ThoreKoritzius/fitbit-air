#include "AirSession.h"
#include <stdlib.h>
#include <string.h>
#include "xp/coap/gg_coap.h"
#include "xp/gattlink/gg_gattlink_generic_client.h"
#include "xp/loop/gg_loop.h"
#include "xp/module/gg_module.h"
#include "xp/stack_builder/gg_stack_builder.h"

struct AirSession {
    GG_IMPLEMENTS(GG_DataSource);
    GG_IMPLEMENTS(GG_DataSink);
    GG_IMPLEMENTS(GG_TlsKeyResolver);
    GG_IMPLEMENTS(GG_EventListener);
    GG_IMPLEMENTS(GG_CoapResponseListener);
    GG_Loop *loop;
    GG_Stack *stack;
    GG_CoapEndpoint *endpoint;
    GG_DataSink *receiver;
    GG_DataSinkListener *sender;
    GG_CoapRequestHandle request;
    void *context;
    AirSend send;
    bool handoff;
    bool reset;
    bool pending;
    int response;
    uint8_t payload[32];
    size_t payload_size;
};

static GG_Result air_set_sink(GG_DataSource *_self, GG_DataSink *sink) {
    AirSession *self = GG_SELF(AirSession, GG_DataSource);
    self->receiver = sink;
    return GG_SUCCESS;
}

static GG_Result air_send(GG_DataSink *_self, GG_Buffer *buffer,
                          const GG_BufferMetadata *metadata) {
    (void)metadata;
    AirSession *self = GG_SELF(AirSession, GG_DataSink);
    if (self->reset) return GG_SUCCESS;
    return self->send(self->context, GG_Buffer_GetData(buffer), GG_Buffer_GetDataSize(buffer))
        ? GG_SUCCESS : GG_ERROR_WOULD_BLOCK;
}

static GG_Result air_set_listener(GG_DataSink *_self, GG_DataSinkListener *listener) {
    AirSession *self = GG_SELF(AirSession, GG_DataSink);
    self->sender = listener;
    return GG_SUCCESS;
}

// The public bootstrap identity and key match Golden Gate's Android bindings.
static GG_Result air_resolve_key(GG_TlsKeyResolver *_self, const uint8_t *identity,
                                 size_t size, uint8_t *key, size_t *key_size) {
    AirSession *self = GG_SELF(AirSession, GG_TlsKeyResolver);
    const uint8_t bootstrap[] = {
        0x81, 0x06, 0x54, 0xe3, 0x36, 0xad, 0xca, 0xb0,
        0xa0, 0x3c, 0x60, 0xf7, 0x4a, 0xa0, 0xb6, 0xfb,
    };
    if (self->handoff || size != 9 || memcmp(identity, "BOOTSTRAP", 9) != 0)
        return GG_ERROR_NO_SUCH_ITEM;
    if (*key_size < sizeof(bootstrap)) {
        *key_size = sizeof(bootstrap);
        return GG_ERROR_NOT_ENOUGH_SPACE;
    }
    memcpy(key, bootstrap, sizeof(bootstrap));
    *key_size = sizeof(bootstrap);
    return GG_SUCCESS;
}

static void air_event(GG_EventListener *_self, const GG_Event *event) {
    AirSession *self = GG_SELF(AirSession, GG_EventListener);
    if (self->handoff && event->type == GG_EVENT_TYPE_GATTLINK_SESSION_RESET)
        self->reset = true;
}

static void air_ack(GG_CoapResponseListener *self) { (void) self; }

static void air_error(GG_CoapResponseListener *_self, GG_Result error, const char *message) {
    (void)message;
    AirSession *self = GG_SELF(AirSession, GG_CoapResponseListener);
    self->pending = false;
    self->response = error;
}

static void air_response(GG_CoapResponseListener *_self, GG_CoapMessage *message) {
    AirSession *self = GG_SELF(AirSession, GG_CoapResponseListener);
    self->pending = false;
    const size_t size = GG_CoapMessage_GetPayloadSize(message);
    self->response = GG_CoapMessage_GetCode(message);
    self->payload_size = size <= sizeof(self->payload) ? size : 0;
    if (self->payload_size)
        memcpy(self->payload, GG_CoapMessage_GetPayload(message), size);
}

GG_IMPLEMENT_INTERFACE(AirSession, GG_DataSource) { .SetDataSink = air_set_sink };
GG_IMPLEMENT_INTERFACE(AirSession, GG_DataSink) {
    .PutData = air_send, .SetListener = air_set_listener,
};
GG_IMPLEMENT_INTERFACE(AirSession, GG_TlsKeyResolver) { .ResolveKey = air_resolve_key };
GG_IMPLEMENT_INTERFACE(AirSession, GG_EventListener) { .OnEvent = air_event };
GG_IMPLEMENT_INTERFACE(AirSession, GG_CoapResponseListener) {
    .OnAck = air_ack, .OnError = air_error, .OnResponse = air_response,
};

AirSession *air_session_open(void *context, AirSend send, bool handoff) {
    static bool initialized = false;
    if (!initialized) {
        if (GG_FAILED(GG_Module_Initialize())) return NULL;
        initialized = true;
    }
    AirSession *self = calloc(1, sizeof(*self));
    if (!self) return NULL;
    self->context = context;
    self->send = send;
    self->handoff = handoff;
    GG_SET_INTERFACE(self, AirSession, GG_DataSource);
    GG_SET_INTERFACE(self, AirSession, GG_DataSink);
    GG_SET_INTERFACE(self, AirSession, GG_TlsKeyResolver);
    GG_SET_INTERFACE(self, AirSession, GG_EventListener);
    GG_SET_INTERFACE(self, AirSession, GG_CoapResponseListener);
    if (GG_FAILED(GG_Loop_Create(&self->loop)) ||
        GG_FAILED(GG_Loop_BindToCurrentThread(self->loop))) goto fail;

    static const uint16_t suites[] = {
        GG_TLS_PSK_WITH_AES_128_CCM,
        GG_TLS_PSK_WITH_AES_128_GCM_SHA256,
        GG_TLS_ECDHE_PSK_WITH_AES_128_CBC_SHA256,
    };
    GG_TlsServerOptions options = {
        .base = { .cipher_suites = suites, .cipher_suites_count = GG_ARRAY_SIZE(suites) },
        .key_resolver = GG_CAST(self, GG_TlsKeyResolver),
    };
    GG_StackBuilderParameters params = {
        .element_type = GG_STACK_ELEMENT_TYPE_DTLS_SERVER, .element_parameters = &options,
    };
    if (GG_FAILED(GG_StackBuilder_BuildStack(GG_STACK_DESCRIPTOR_DTLS_SOCKET_NETIF_GATTLINK,
        &params, 1, GG_STACK_ROLE_HUB, NULL, self->loop, GG_CAST(self, GG_DataSource),
        GG_CAST(self, GG_DataSink), &self->stack))) goto fail;
    GG_EventEmitter_SetListener(GG_Stack_AsEventEmitter(self->stack),
                                GG_CAST(self, GG_EventListener));
    if (!handoff) {
        GG_StackElementPortInfo port;
        if (GG_FAILED(GG_Stack_GetPortById(self->stack, GG_STACK_ELEMENT_ID_TOP,
            GG_STACK_PORT_ID_TOP, &port))) goto fail;
        if (GG_FAILED(GG_CoapEndpoint_Create(GG_Loop_GetTimerScheduler(self->loop),
            port.sink, port.source, &self->endpoint))) goto fail;
    }
    if (GG_FAILED(GG_Stack_Start(self->stack))) goto fail;
    return self;
fail:
    air_session_close(self);
    return NULL;
}

void air_session_close(AirSession *self) {
    if (!self) return;
    if (self->endpoint) GG_CoapEndpoint_Destroy(self->endpoint);
    if (self->stack) {
        GG_EventEmitter_SetListener(GG_Stack_AsEventEmitter(self->stack), NULL);
        GG_Stack_Destroy(self->stack);
    }
    if (self->loop) GG_Loop_Destroy(self->loop);
    free(self);
}

void air_session_poll(AirSession *self) {
    uint32_t next;
    GG_Loop_DoWork(self->loop, 0, &next);
}

void air_session_receive(AirSession *self, const uint8_t *bytes, size_t count) {
    if (!self->receiver || self->reset) return;
    GG_DynamicBuffer *buffer = NULL;
    if (GG_FAILED(GG_DynamicBuffer_Create(count, &buffer))) return;
    if (GG_SUCCEEDED(GG_DynamicBuffer_SetData(buffer, bytes, count)))
        GG_DataSink_PutData(self->receiver, GG_DynamicBuffer_AsBuffer(buffer), NULL);
    GG_DynamicBuffer_Release(buffer);
}

void air_session_can_send(AirSession *self) {
    if (self->sender && !self->reset) GG_DataSinkListener_OnCanPut(self->sender);
}

bool air_session_ready(AirSession *self) {
    GG_DtlsProtocolStatus status;
    return GG_SUCCEEDED(GG_Stack_GetDtlsProtocolStatus(self->stack, &status))
        && status.state == GG_TLS_STATE_SESSION;
}

bool air_session_reset_observed(AirSession *self) { return self->reset; }

int air_session_request(AirSession *self, bool write, const uint8_t *bytes, size_t count) {
    if (self->pending || !self->endpoint) return GG_ERROR_INVALID_STATE;
    self->response = 0;
    self->payload_size = 0;
    GG_CoapMessageOptionParam path = GG_COAP_MESSAGE_OPTION_PARAM_STRING(URI_PATH, "settings");
    const GG_CoapClientParameters params = { .ack_timeout = 2500, .max_resend_count = 0 };
    self->pending = true;
    GG_Result result = GG_CoapEndpoint_SendRequest(self->endpoint,
        write ? GG_COAP_METHOD_POST : GG_COAP_METHOD_PUT, &path, 1, bytes, count,
        &params, GG_CAST(self, GG_CoapResponseListener), &self->request);
    if (GG_FAILED(result)) self->pending = false;
    return result;
}

int air_session_response(AirSession *self, uint8_t *bytes, size_t *count) {
    size_t size = self->payload_size <= *count ? self->payload_size : 0;
    if (size) memcpy(bytes, self->payload, size);
    *count = size;
    return self->response;
}

void air_session_cancel_request(AirSession *self) {
    if (self->pending) {
        GG_CoapEndpoint_CancelRequest(self->endpoint, self->request);
        self->pending = false;
    }
}
