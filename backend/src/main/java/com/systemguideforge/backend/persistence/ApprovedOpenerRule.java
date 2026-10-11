package com.systemguideforge.backend.persistence;

/**
 * A human-approved control that the analysis may press only to reveal a form: the route template of the page that
 * holds it (numeric and UUID segments written as {@code {id}}) and the control's visible accessible name.
 */
public record ApprovedOpenerRule(String routePattern, String accessibleName) {}
