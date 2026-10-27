package com.overload.overload_server.security;

import java.io.Serializable;
import java.security.Principal;
import java.util.Objects;
import java.util.UUID;

public record SessionPrincipal(UUID userId) implements Principal, Serializable {

	public SessionPrincipal {
		Objects.requireNonNull(userId, "userId");
	}

	@Override
	public String getName() {
		return userId.toString();
	}

}
