package ar.edu.utn.frba.arbiter.auth.dto;

import java.time.Instant;

/** @param version when the logo was uploaded: the front uses it to tell a new logo from a cached one */
public record InsurerLogoResponse(Instant version) {}
