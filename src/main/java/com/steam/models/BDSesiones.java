package com.steam.models;

import java.util.ArrayList;
import java.util.List;

/** Raíz del JSON persistido en SES_Main.txt / SES_Copy.txt */
public class BDSesiones {
    public List<Usuario> usuarios = new ArrayList<>();
    public List<Sesion>  sesiones = new ArrayList<>();
}
