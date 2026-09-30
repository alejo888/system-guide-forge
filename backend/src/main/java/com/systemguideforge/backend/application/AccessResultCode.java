package com.systemguideforge.backend.application;

/** Stable machine codes for an access test; clients translate them instead of showing the English message. */
public enum AccessResultCode { LOGIN_FORM_UNAVAILABLE, AUTHENTICATED, NOT_AUTHENTICATED, BROWSER_LOGIN_FAILED, HOST_REACHABLE, HOST_UNREACHABLE }
