(ns fukan.vocabulary.boundary.analyzer
  "Boundary AST → kernel content. Per MODEL.md §8.2.

   Two file shapes (Plan 3a parser AST):
   - Module-bound: declarations are mix of :use, :fn, :exports.
   - Subsystem-bound: declarations are use + one :subsystem.

   Task 3 adds the build require when the fn handler starts emitting
   kernel content.

   This namespace is built up across Tasks 2-7.")

;; ---------------------------------------------------------------------------
;; Shape detection
;; ---------------------------------------------------------------------------

(defn- shape-of
  "Returns :module-bound, :subsystem-bound, or :mixed (a structural error)."
  [declarations]
  (let [kinds (set (map :type declarations))
        has-module-decls    (or (kinds :fn) (kinds :exports))
        has-subsystem-decls (kinds :subsystem)]
    (cond
      (and has-module-decls has-subsystem-decls) :mixed
      has-subsystem-decls                        :subsystem-bound
      :else                                      :module-bound)))

;; ---------------------------------------------------------------------------
;; Per-decl handlers (Tasks 3-7 fill these in)
;; ---------------------------------------------------------------------------

(defn- analyze-use [model _decl _coord _use-aliases]
  ;; use declarations are analyzer-internal (handled at pipeline level
  ;; via use-aliases); no kernel content produced here.
  model)

(defn- analyze-fn [model _decl _coord _use-aliases]
  ;; Tasks 3-5 implement.
  model)

(defn- analyze-exports [model _decl _coord _use-aliases]
  ;; Task 6 implements.
  model)

(defn- analyze-subsystem [model _decl _coord _use-aliases]
  ;; Task 7 implements.
  model)

(defn- analyze-decl [model decl coord use-aliases]
  (case (:type decl)
    :use       (analyze-use       model decl coord use-aliases)
    :fn        (analyze-fn        model decl coord use-aliases)
    :exports   (analyze-exports   model decl coord use-aliases)
    :subsystem (analyze-subsystem model decl coord use-aliases)
    (throw (ex-info "Unknown .boundary declaration type"
                    {:type :boundary-shape-error
                     :decl-type (:type decl)
                     :coord coord}))))

;; ---------------------------------------------------------------------------
;; Public entrypoint
;; ---------------------------------------------------------------------------

(defn analyze-file
  "Apply a parsed .boundary AST to the model. `coord` is the file's
   coordinate (root-relative, no extension). `use-aliases` is the
   file-local map of alias → canonical-coord for cross-module resolution.

   Returns updated model. Throws on mixed module/subsystem shape."
  [model ast coord use-aliases]
  (let [decls (:declarations ast)
        shape (shape-of decls)]
    (when (= shape :mixed)
      (throw (ex-info "mixed module-bound and subsystem-bound shapes in one file"
                      {:type :boundary-shape-error
                       :coord coord})))
    (reduce (fn [m decl] (analyze-decl m decl coord use-aliases))
            model
            decls)))
