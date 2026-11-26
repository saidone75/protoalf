package org.saidone.protoalf.platform;

import io.grpc.*;
import lombok.val;
import org.alfresco.service.cmr.security.AuthenticationService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.saidone.protoalf.api.NodeServiceGrpc;

import static org.mockito.Mockito.*;

class CallLoggingInterceptorTest {

    @Test
    @SuppressWarnings("unchecked")
    void closeIsForwardedToTheOriginalCall() {
        val call = mock(ServerCall.class);
        when(call.getMethodDescriptor()).thenReturn((MethodDescriptor) NodeServiceGrpc.getGetNodeMethod());
        val handler = mock(ServerCallHandler.class);
        val headers = new Metadata();
        new CallLoggingInterceptor().interceptCall(call, headers, handler);
        val wrapped = ArgumentCaptor.forClass(ServerCall.class);
        verify(handler).startCall(wrapped.capture(), eq(headers));
        val trailers = new Metadata();
        wrapped.getValue().close(Status.NOT_FOUND.withDescription("Node not found"), trailers);
        verify(call).close(argThat(s -> s.getCode() == Status.Code.NOT_FOUND), same(trailers));
    }

    @Test
    @SuppressWarnings("unchecked")
    void callsRejectedByTheTicketInterceptorAreStillClosed() {
        val call = mock(ServerCall.class);
        when(call.getMethodDescriptor()).thenReturn((MethodDescriptor) NodeServiceGrpc.getGetNodeMethod());
        val handler = mock(ServerCallHandler.class);
        val service = ServerInterceptors.intercept(ServerServiceDefinition.builder(NodeServiceGrpc.SERVICE_NAME)
                .addMethod(NodeServiceGrpc.getGetNodeMethod(), handler).build(), new TicketInterceptor(mock(AuthenticationService.class)), new CallLoggingInterceptor());
        val intercepted = (ServerCallHandler<Object, Object>) service.getMethod(NodeServiceGrpc.getGetNodeMethod().getFullMethodName()).getServerCallHandler();
        intercepted.startCall(call, new Metadata());
        verify(call).close(argThat(s -> s.getCode() == Status.Code.UNAUTHENTICATED), any());
        verifyNoInteractions(handler);
    }
}
