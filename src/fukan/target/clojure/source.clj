(ns fukan.target.clojure.source
  "Clojure source walker + top-level form reader.

   Identifies (def Name ...) and (defn name ...) top-level forms,
   reads them as data (no eval), and returns Code.* candidate records.

   MVP scope: literal def/defn only. Macros that expand to def/defn
   (defmulti, etc.) are out of scope — they surface as unprojected code
   per DESIGN.md 'Couplings'."
  (:require [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.io PushbackReader]))

(defn find-clj-files
  "Walk a root directory, return absolute paths to all .clj files (sorted
   deterministically)."
  [root]
  (->> (file-seq (io/file root))
       (filter #(.isFile %))
       (filter #(str/ends-with? (.getName %) ".clj"))
       (sort-by #(.getCanonicalPath %))
       (mapv #(.getPath %))))

(defn read-forms
  "Read all top-level forms from a Clojure file as data. Each form is the
   s-expression value (no eval)."
  [path]
  (binding [*read-eval* false]
    (with-open [rdr (io/reader path)]
      (let [pbr (PushbackReader. rdr)
            eof (Object.)]
        (loop [acc []]
          (let [f (read {:eof eof :read-cond :allow :features #{:clj}} pbr)]
            (if (identical? f eof)
              acc
              (recur (conj acc f)))))))))

(defn- ns-of-forms
  "Find the (ns ...) form among top-level forms, return its second element
   as a string. nil if no ns form."
  [forms]
  (some (fn [f]
          (when (and (list? f) (= 'ns (first f)) (symbol? (second f)))
            (str (second f))))
        forms))

;; ---------------------------------------------------------------------------
;; Field extraction — read def bodies for record/schema field shape
;; ---------------------------------------------------------------------------
;;
;; Two recognised shapes:
;;
;;   1. Malli `[:map …]` schema — the most common case in fukan:
;;        (def ServerOpts
;;          [:map {:description "..."}            ; optional options map
;;           [:port {:optional true} :int]        ; per-entry options map ok
;;           [:host :string]
;;           [:handler fn?]])                     ; non-keyword type → :any
;;
;;      Fields: [[:port :int] [:host :string] [:handler :any]].
;;
;;   2. `defrecord` — positional symbol list, no carried types:
;;        (defrecord Order [id customer items total])
;;
;;      Fields: [[:id :any] [:customer :any] [:items :any] [:total :any]].
;;
;; Any other def shape (single keyword, value literal, fn, etc.) yields no
;; `:fields`. Shape extraction is best-effort — unrecognised forms simply
;; don't produce a `:fields` slot on the symbol record.

(defn- field-name->keyword
  "Normalize a field name to a keyword. defrecord fields arrive as symbols;
   Malli schema entries arrive as keywords already."
  [n]
  (cond
    (keyword? n) n
    (symbol? n)  (keyword (name n))
    (string? n)  (keyword n)
    :else        (keyword (str n))))

(defn- malli-entry-type
  "Pull the type expression out of one Malli :map entry vector.
   An entry is one of:
     [:field :type]                       → :type
     [:field {opts} :type]                → :type (skip options map)
   The :type slot may be:
     :keyword                             → returned as-is
     [:keyword …]                         → first keyword of the inner vector
     symbol/other                         → :any (non-keyword types not handled)

   When the per-entry options map carries `{:optional true}`, the
   returned type is wrapped as `[:maybe T]` so the drift comparator's
   canonical-shape normalisation sees the optional intent (Phase 7
   Task 4 gap 4 — previously the modifier was silently dropped, which
   surfaced every canvas-side `(optional :T)` field as shape-drift
   against its apparent non-optional code counterpart)."
  [entry]
  (let [tail (rest entry)
        opts (when (and (seq tail) (map? (first tail))) (first tail))
        type-slot (if opts (second tail) (first tail))
        optional? (true? (:optional opts))
        ;; When `{:optional true}` carries the optional intent, preserve
        ;; the full compound type-slot rather than collapsing it to its
        ;; head keyword — otherwise wrapping with `[:maybe …]` produces
        ;; nonsense like `[:maybe :maybe]` for an already-`[:maybe T]`
        ;; type-slot, or `[:maybe :sequential]` for `[:sequential T]`.
        ;; The canonical-shape comparator recurses into compound vectors,
        ;; so passing the whole compound preserves both the optional
        ;; intent and the inner type information.
        bare-type (cond
                    (keyword? type-slot)
                    type-slot

                    (and (vector? type-slot) (keyword? (first type-slot)))
                    (if optional? type-slot (first type-slot))

                    :else :any)]
    (cond
      ;; Already `[:maybe T]` — `{:optional true}` is redundant; don't
      ;; double-wrap. Either way the shape is "optional T".
      (and optional?
           (vector? bare-type)
           (= :maybe (first bare-type)))
      bare-type

      optional?
      [:maybe bare-type]

      :else
      bare-type)))

(defn- parse-malli-map-fields
  "Given the body of a (def Name [:map …]) form (the [:map …] vector),
   return a vector of [field-name-kw type-name-kw] pairs.
   Skips a leading options map (e.g. [:map {:description ...} …]).
   Returns nil if the body isn't a Malli :map form."
  [body]
  (when (and (vector? body) (= :map (first body)))
    (let [entries (rest body)
          entries (if (and (seq entries) (map? (first entries)))
                    (rest entries)
                    entries)]
      (->> entries
           (keep (fn [entry]
                   (when (and (vector? entry) (>= (count entry) 2))
                     [(field-name->keyword (first entry))
                      (malli-entry-type entry)])))
           vec))))

(defn- unwrap-schema-wrapper
  "Unwrap one layer of `(m/schema X)` or `(malli/schema X)` so the
   underlying [:map …] literal becomes visible. Many fukan defs wrap
   the schema in `(m/schema [:map …])` so other Malli functions can
   later refer to the schema instance instead of the literal."
  [body]
  (if (and (seq? body)
           (symbol? (first body))
           (= "schema" (name (first body))))
    (second body)
    body))

(defn- def-body
  "Extract the body of a `(def Name body)` or `(def Name \"docstring\" body)`
   form. Skips the docstring when present."
  [form]
  (let [third (nth form 2 nil)
        fourth (nth form 3 nil)]
    (if (and (string? third) (some? fourth))
      fourth
      third)))

(defn- def-fields
  "Look at a `(def Name body)` (or `(def Name \"doc\" body)`) form; if the
   body is a Malli `:map` literal (optionally wrapped in `(m/schema …)`),
   return the parsed `[field-name field-type]` pairs; otherwise nil."
  [form]
  (parse-malli-map-fields (unwrap-schema-wrapper (def-body form))))

(defn- defrecord-fields
  "Given a (defrecord Name [field1 field2 …]) form, return
   [[:field1 :any] [:field2 :any] …]. Returns nil if the field vector
   isn't where we expect it."
  [form]
  (let [field-vec (nth form 2 nil)]
    (when (and (vector? field-vec) (every? symbol? field-vec))
      (mapv (fn [s] [(field-name->keyword s) :any]) field-vec))))

(defn extract-symbols
  "Read a Clojure file and return a vector of
     {:kind :function|:data-structure|:function-private|:property-test
      :ns <string> :name <string> :file <path>}
   records for every top-level def / defn / defn- / defrecord / defspec.

   When the def body is a Malli `[:map …]` schema OR the form is a defrecord,
   a `:fields` slot is added carrying a vector of
   `[field-name-kw type-name-kw]` pairs.

   `defspec` forms (Phase 8 Sprint 5 — `clojure.test.check` property tests)
   surface as `:kind :property-test`. The walker recognises them on the
   same convention as `defn` / `defrecord`: a top-level list whose head is
   the form-keyword and whose second element is the test symbol."
  [path]
  (let [forms (read-forms path)
        ns-name (or (ns-of-forms forms) "")]
    (vec
      (keep (fn [f]
              (when (and (list? f) (symbol? (first f)) (symbol? (second f)))
                (case (str (first f))
                  "def"   (let [fields (def-fields f)]
                            (cond-> {:kind :data-structure
                                     :ns ns-name
                                     :name (str (second f))
                                     :file path}
                              (some? fields) (assoc :fields fields)))
                  "defrecord" (let [fields (defrecord-fields f)]
                                (cond-> {:kind :data-structure
                                         :ns ns-name
                                         :name (str (second f))
                                         :file path}
                                  (some? fields) (assoc :fields fields)))
                  "defn"  {:kind :function
                           :ns ns-name
                           :name (str (second f))
                           :file path}
                  "defn-" {:kind :function-private
                           :ns ns-name
                           :name (str (second f))
                           :file path}
                  "defspec" {:kind :property-test
                             :ns ns-name
                             :name (str (second f))
                             :file path}
                  nil)))
            forms))))
