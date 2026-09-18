package com.builtbyjuls.arat.identity.testing;

import com.builtbyjuls.arat.identity.api.PlatformRole;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.security.test.context.support.WithSecurityContext;

@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@WithSecurityContext(factory = WithAratActorSecurityContextFactory.class)
public @interface WithAratActor {

    AratAccountFixture account() default AratAccountFixture.OWNER;

    String accountId() default "";

    PlatformRole[] platformRoles() default {};
}
