package com.builtbyjuls.arat.identity.security;

import com.builtbyjuls.arat.identity.api.CurrentActor;
import com.builtbyjuls.arat.identity.api.PlatformRole;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("local & !prod")
@RequestMapping("/api/v1/dev")
class LocalDevelopmentController {

    private final CurrentActor currentActor;

    LocalDevelopmentController(CurrentActor currentActor) {
        this.currentActor = currentActor;
    }

    @GetMapping("/whoami")
    WhoAmIResponse whoami() {
        var actor = currentActor.requireAuthenticatedActor();
        return new WhoAmIResponse(actor.accountId(), actor.platformRoles());
    }

    record WhoAmIResponse(UUID actorId, Set<PlatformRole> platformRoles) {
    }
}
