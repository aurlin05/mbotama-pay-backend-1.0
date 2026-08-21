package com.mbotamapay.routing;

import lombok.Getter;

import java.util.List;

/**
 * Aucune route exécutable pour ce corridor.
 *
 * <p>
 * Porte les motifs de rejet, porte par porte. Le message précédent —
 * « Aucune route viable (toutes sous le seuil de score) » — ne permettait ni de
 * diagnostiquer ni de répondre au client.
 */
@Getter
public class NoRouteAvailableException extends RuntimeException {

    private final transient List<Eligibility.Rejection> rejections;
    private final String corridor;

    public NoRouteAvailableException(String corridor, List<Eligibility.Rejection> rejections, String message) {
        super(message);
        this.corridor = corridor;
        this.rejections = List.copyOf(rejections);
    }

    /** Motifs détaillés, un par route écartée. */
    public List<String> details() {
        return rejections.stream().map(Eligibility.Rejection::describe).toList();
    }
}
