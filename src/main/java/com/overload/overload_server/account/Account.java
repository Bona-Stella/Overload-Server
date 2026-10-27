package com.overload.overload_server.account;

import java.util.UUID;

public record Account(UUID id, long dataGeneration, String nickname) {
}
