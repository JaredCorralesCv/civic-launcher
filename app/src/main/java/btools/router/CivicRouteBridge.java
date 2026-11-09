package btools.router;

import java.util.List;

/**
 * Puente con BRouter: las indicaciones de giro (VoiceHint) tienen sus campos visibles solo dentro de
 * este paquete, así que esta clase vive aquí y las entrega como números simples a Civic.
 */
public final class CivicRouteBridge {
    private CivicRouteBridge() {}

    /**
     * Cada indicación como {índice del punto en la ruta, comando, salida de glorieta, ángulo×10}.
     * Comandos (los de BRouter): 1 seguir, 2 izquierda, 3 ligera izq., 4 cerrada izq., 5 derecha,
     * 6 ligera der., 7 cerrada der., 8 mantener izq., 9 mantener der., 10/11/15 vuelta en U,
     * 13/14 glorieta, 17 salida izq., 18 salida der., 100 llegada.
     */
    public static int[][] hints(OsmTrack track) {
        if (track == null || track.voiceHints == null || track.voiceHints.list == null) return new int[0][];
        List<VoiceHint> list = track.voiceHints.list;
        int[][] out = new int[list.size()][];
        for (int i = 0; i < list.size(); i++) {
            VoiceHint h = list.get(i);
            out[i] = new int[] {h.indexInTrack, h.cmd, h.roundaboutExit, Math.round(h.angle * 10)};
        }
        return out;
    }
}
