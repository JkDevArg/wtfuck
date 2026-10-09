// La cola de turnos: un bot de herramientas corre UNA cosa a la vez. Mientras
// alguien tiene el turno, el bot trabaja solo para esa persona; los demas
// esperan y se les avisa su posicion y cuando les toca.
//
// El turno se suelta de tres formas:
//   - la persona escribe `/fin`;
//   - pasa `inactividadMs` sin que haga nada (se cayo, se olvido);
//   - el runner lo libera al terminar una tarea, si asi se configura.
//
// Es logica pura (sin red): asi se prueba sola. Ver cola.test.ts.

export interface EstadoCola {
  activo: string | null;
  espera: string[];
}

export class Cola {
  private activo: string | null = null;
  private readonly espera: string[] = [];
  private temporizador: ReturnType<typeof setTimeout> | null = null;

  /**
   * @param inactividadMs cuanto esperar sin actividad antes de soltar el turno.
   *   0 = sin limite de inactividad.
   * @param alPasarTurno se llama cuando el turno pasa a otra persona (o a nadie):
   *   el runner avisa al nuevo que le toca.
   */
  constructor(
    private readonly inactividadMs: number,
    private readonly alPasarTurno: (siguiente: string | null) => void,
  ) {}

  /**
   * Pide el turno para `quien`. Devuelve `'activo'` si ya es suyo (o se lo
   * acaba de dar), o el numero de posicion en la cola (1 = el proximo).
   */
  pedir(quien: string): 'activo' | number {
    if (this.activo === quien) {
      this.reiniciarInactividad();
      return 'activo';
    }
    if (this.activo === null) {
      this.activo = quien;
      this.reiniciarInactividad();
      return 'activo';
    }
    if (!this.espera.includes(quien)) this.espera.push(quien);
    return this.espera.indexOf(quien) + 1;
  }

  /** Marca actividad de quien tiene el turno, para que no se le venza. */
  tocar(quien: string): void {
    if (this.activo === quien) this.reiniciarInactividad();
  }

  /**
   * Suelta el turno de `quien`. Si lo tenia, pasa al siguiente (y se avisa por
   * `alPasarTurno`). Si solo estaba esperando, lo saca de la cola.
   */
  liberar(quien: string): void {
    if (this.activo !== quien) {
      const i = this.espera.indexOf(quien);
      if (i >= 0) this.espera.splice(i, 1);
      return;
    }
    this.pasarAlSiguiente();
  }

  estado(): EstadoCola {
    return { activo: this.activo, espera: [...this.espera] };
  }

  /** Posicion de `quien` en la espera (1 = proximo), o 0 si no esta esperando. */
  posicion(quien: string): number {
    return this.espera.indexOf(quien) + 1;
  }

  private pasarAlSiguiente(): void {
    if (this.temporizador) {
      clearTimeout(this.temporizador);
      this.temporizador = null;
    }
    this.activo = this.espera.shift() ?? null;
    if (this.activo) this.reiniciarInactividad();
    this.alPasarTurno(this.activo);
  }

  private reiniciarInactividad(): void {
    if (this.temporizador) clearTimeout(this.temporizador);
    this.temporizador = null;
    if (this.activo && this.inactividadMs > 0) {
      this.temporizador = setTimeout(() => this.pasarAlSiguiente(), this.inactividadMs);
      // Que un turno ocioso no impida cerrar el proceso.
      this.temporizador.unref?.();
    }
  }
}
