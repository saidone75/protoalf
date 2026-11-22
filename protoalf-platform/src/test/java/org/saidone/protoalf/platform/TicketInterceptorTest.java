package org.saidone.protoalf.platform;

import io.grpc.*;
import lombok.val;
import org.alfresco.service.cmr.security.AuthenticationService;
import org.junit.jupiter.api.Test;
import org.saidone.protoalf.api.AuthenticationServiceGrpc;
import org.saidone.protoalf.api.NodeServiceGrpc;

import static org.mockito.Mockito.*;

class TicketInterceptorTest {

    @Test
    void missingTicketIsRejectedBeforeHandler() {
        val auth = mock(AuthenticationService.class);
        val call = mock(ServerCall.class);
        val handler = mock(ServerCallHandler.class);
        new TicketInterceptor(auth).interceptCall(call, new Metadata(), handler);
        verify(call).close(argThat(s -> s.getCode() == Status.Code.UNAUTHENTICATED), any());
        verifyNoInteractions(auth, handler);
    }

    @Test
    void loginIsLetThroughWithoutTicket() {
        val auth = mock(AuthenticationService.class);
        val call = mock(ServerCall.class);
        val handler = mock(ServerCallHandler.class);
        when(call.getMethodDescriptor()).thenReturn((MethodDescriptor) AuthenticationServiceGrpc.getLoginMethod());
        val headers = new Metadata();
        new TicketInterceptor(auth).interceptCall(call, headers, handler);
        verify(handler).startCall(call, headers);
        verify(call, never()).close(any(), any());
        verifyNoInteractions(auth);
    }

    @Test
    void everyOtherMethodStillNeedsTheTicket() {
        val auth = mock(AuthenticationService.class);
        val call = mock(ServerCall.class);
        val handler = mock(ServerCallHandler.class);
        when(call.getMethodDescriptor()).thenReturn((MethodDescriptor) NodeServiceGrpc.getDownloadContentMethod());
        new TicketInterceptor(auth).interceptCall(call, new Metadata(), handler);
        verify(call).close(argThat(s -> s.getCode() == Status.Code.UNAUTHENTICATED), any());
        verifyNoInteractions(auth, handler);
    }
}
