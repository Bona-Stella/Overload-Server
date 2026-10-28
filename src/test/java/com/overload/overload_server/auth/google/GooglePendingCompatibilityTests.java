package com.overload.overload_server.auth.google;

import com.overload.overload_server.security.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.serializer.support.*;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import java.nio.file.*;
import java.time.Instant;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

class GooglePendingCompatibilityTests {
    @TempDir Path directory;
    @Test void readsThePreviousFiveFieldPendingFormatWithoutGrantingLinkEvidence() throws Exception {
        var source=directory.resolve("GoogleBoundRequests.java");
        Files.writeString(source,"""
                package com.overload.overload_server.auth.google;
                class GoogleBoundRequests {
                    protected record Pending(String purpose,
                        com.overload.overload_server.security.SessionReauthentication.Snapshot snapshot,
                        java.util.UUID identity,java.time.Instant startedAt,
                        org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest authorization)
                        implements java.io.Serializable { }
                }
                """);
        var main=Path.of(SessionReauthentication.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        var oauth=Path.of(OAuth2AuthorizationRequest.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        var servlet=Path.of(jakarta.servlet.http.HttpServletRequest.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        String classpath=main+java.io.File.pathSeparator+oauth+java.io.File.pathSeparator+servlet;
        var errors=new java.io.ByteArrayOutputStream();
        assertThat(javax.tools.ToolProvider.getSystemJavaCompiler().run(null,null,errors,"-classpath",classpath,"-d",directory.toString(),source.toString())).isZero();
        var now=Instant.now();var user=UUID.randomUUID();var identity=UUID.randomUUID();
        var snapshot=new SessionReauthentication.Snapshot("synthetic-session",user,new SessionMetadata(user,now,now.plusSeconds(90*86400L),now));
        var authorization=OAuth2AuthorizationRequest.authorizationCode().authorizationUri("https://accounts.google.com/o/oauth2/v2/auth")
                .clientId("synthetic-client").redirectUri("http://localhost:8080/login/oauth2/code/google-contact").state("synthetic-state").build();
        try(var loader=new java.net.URLClassLoader(new java.net.URL[]{directory.toUri().toURL()},getClass().getClassLoader()){
            @Override protected Class<?> loadClass(String name,boolean resolve)throws ClassNotFoundException{
                if(name.startsWith("com.overload.overload_server.auth.google.GoogleBoundRequests")){
                    var type=findLoadedClass(name);if(type==null){type=findClass(name);}if(resolve){resolveClass(type);}return type;
                }
                return super.loadClass(name,resolve);
            }
        }){
            var type=loader.loadClass(GoogleBoundRequests.class.getName()+"$Pending");
            var constructor=type.getDeclaredConstructor(String.class,SessionReauthentication.Snapshot.class,UUID.class,Instant.class,OAuth2AuthorizationRequest.class);constructor.setAccessible(true);
            var old=(java.io.Serializable)constructor.newInstance("GOOGLE_CONTACT",snapshot,identity,now,authorization);
            var restored=(GoogleBoundRequests.Pending)new DeserializingConverter().convert(new SerializingConverter().convert(old));
            assertThat(restored.snapshot()).isEqualTo(snapshot);assertThat(restored.identity()).isEqualTo(identity);assertThat(restored.purpose()).isEqualTo("GOOGLE_CONTACT");
            assertThat(restored.authorization().getState()).isEqualTo("synthetic-state");assertThat(restored.emailProof()).isNull();assertThat(restored.identityVersion()).isZero();
        }
    }
}
