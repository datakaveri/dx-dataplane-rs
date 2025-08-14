package org.cdpg.dx.essearch.model;

public record TextSearchRequest(String q, Boolean fuzzy, Boolean autoComplete) {}
