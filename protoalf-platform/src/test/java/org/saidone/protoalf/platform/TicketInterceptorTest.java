package org.saidone.protoalf.platform;
import io.grpc.*;
import org.alfresco.service.cmr.security.AuthenticationService;
import org.junit.jupiter.api.Test;
import org.saidone.protoalf.api.AuthenticationServiceGrpc;
import org.saidone.protoalf.api.NodeServiceGrpc;
import static org.mockito.Mockito.*;
class TicketInterceptorTest {
 @Test void missingTicketIsRejectedBeforeHandler() {
  var auth=mock(AuthenticationService.class);
  ServerCall<Object,Object> call=mock(ServerCall.class);
  ServerCallHandler<Object,Object> handler=mock(ServerCallHandler.class);
  new TicketInterceptor(auth).interceptCall(call,new Metadata(),handler);
  verify(call).close(argThat(s -> s.getCode()==Status.Code.UNAUTHENTICATED),any());
  verifyNoInteractions(auth,handler);
 }
 @Test void loginIsLetThroughWithoutTicket() {
  var auth=mock(AuthenticationService.class);
  ServerCall<Object,Object> call=mock(ServerCall.class);
  ServerCallHandler<Object,Object> handler=mock(ServerCallHandler.class);
  when(call.getMethodDescriptor()).thenReturn((MethodDescriptor) AuthenticationServiceGrpc.getLoginMethod());
  var headers=new Metadata();
  new TicketInterceptor(auth).interceptCall(call,headers,handler);
  verify(handler).startCall(call,headers);
  verify(call,never()).close(any(),any());
  verifyNoInteractions(auth);
 }
 @Test void everyOtherMethodStillNeedsTheTicket() {
  var auth=mock(AuthenticationService.class);
  ServerCall<Object,Object> call=mock(ServerCall.class);
  ServerCallHandler<Object,Object> handler=mock(ServerCallHandler.class);
  when(call.getMethodDescriptor()).thenReturn((MethodDescriptor) NodeServiceGrpc.getDownloadContentMethod());
  new TicketInterceptor(auth).interceptCall(call,new Metadata(),handler);
  verify(call).close(argThat(s -> s.getCode()==Status.Code.UNAUTHENTICATED),any());
  verifyNoInteractions(auth,handler);
 }
}
