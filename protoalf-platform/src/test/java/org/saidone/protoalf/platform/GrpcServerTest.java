package org.saidone.protoalf.platform;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class GrpcServerTest {
 @Test void rejectsRemoteCleartext() {
  var server=new GrpcServer(null,true,"0.0.0.0",9090,2,16777216,"","");
  assertThrows(IllegalArgumentException.class,server::start);
  server.stop();
 }
 @Test void rejectsPartialTlsConfiguration() {
  var server=new GrpcServer(null,true,"127.0.0.1",9090,2,16777216,"cert.pem","");
  assertThrows(IllegalArgumentException.class,server::start);
 }
 @Test void disabledServerDoesNotOpenSocket() throws Exception {
  var server=new GrpcServer(null,false,"127.0.0.1",9090,2,16777216,"",""); server.start();server.stop();
 }
}
