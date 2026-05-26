(ns canvas.web.views.shell
  "Canvas port of web/views/shell.allium + shell.boundary.

   Coverage:
     - fn render_app_shell → construction/function (boundary)

   Notes:
     - shell.allium body is scope-only prose (no behavioral declarations).
     - Single boundary function: () → Html."
  (:require [fukan.canvas.core.helpers :as h]
            [fukan.canvas.construction :refer [function]]))

(defn build-canvas []
  (h/with-canvas
    (h/within-module "web.views.shell"

      ;; ── Boundary Functions ────────────────────────────────────────────────

      (function "render_app_shell"
        "Render the initial HTML shell for the application."
        (takes [])
        (gives :Html)))))
