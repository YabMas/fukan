(ns fukan.common.extraction.core
  "The Clojure code-structure extractor — fukan parsing its OWN `src/`. The PL-specific half of the
   extraction seam: the only place that knows Clojure. It reads clj-kondo's `:analysis` output and
   maps Clojure constructs onto the code vocab:

     ns                      → Module      (a cohesion boundary)
     defn / defn- / defmulti → Operation   (a unit of computation — defmulti is a dispatch point)
     defmethod               → Fulfilment  (a satisfier — the supply half of a call)

   The extraction seam is its OWN area (`fukan.common.extraction/`), NOT part of the code vocabulary it
   populates: it mints no structures, it realizes the `fukan.model.extraction` plug-point. The
   Clojure-specific Operation mapping, effect classification, and Module assembly (the generic
   extracted-root wrapper) all live under `fukan.common.extraction.clojure.*`. This namespace is
   the shared orchestration — run clj-kondo, group, call the element builders → the engine-agnostic
   FACTS `{:roots …}`. Every extracted entity is a FLAT root with a natural-key id (`\"ns\"` for a
   Module, `\"ns/op\"` for an Operation), and cross-references — a Module's `:child`, an Operation's
   `:calls` — are `substrate/Ref`s BY that id, so the ONE assembler links them exactly as it links
   authored var-refs (dedup by id, no post-build eid-arithmetic pass). The extractor OWNS no
   vocabulary — it EMITS instances by tag (the BUILD stamps provenance at the merge). It is the HOOK for
   the `fukan.model.extraction` plug-point; the composition root registers `extract-roots` as the fact
   extractor. clj-kondo is the wheel we don't reinvent."
  (:require [clj-kondo.core :as kondo]
            [clojure.tools.reader :as reader]
            [clojure.tools.reader.reader-types :as reader-types]
            [fukan.common.extraction.clojure.effect :as clj-effect]
            [fukan.common.extraction.clojure.method :as clj-method]
            [fukan.common.extraction.clojure.module :as clj-module]
            [fukan.common.extraction.clojure.operation :as clj-operation]))

(defn- analyze
  "Run clj-kondo over `paths` and return its `:analysis` — namespace + var
   definitions. Reads source (and writes clj-kondo's cache); deterministic output."
  [paths]
  (:analysis (kondo/run! {:lint (vec paths)
                          :config {:output {:analysis {:var-definitions {:meta true}
                                                       :var-usages true}}}})))

(defn- alias-ns
  "The namespace an auto-resolved alias reads as, for the extent read below: one PER ALIAS, minted
   under a reserved prefix that no real namespace answers to.

   Distinctness is the whole requirement, and it is not cosmetic. Sending every alias to one
   namespace makes `{::a/id 1 ::b/id 2}` — ordinary Clojure — two copies of one keyword, which the
   reader rejects as a duplicate key; so does `#{::a/x ::b/x}`. That is a read failure this
   extractor CAUSED, on a file that is perfectly readable, and it costs the whole file's extents.
   The distinction matters because the drop-on-failure below is the answer to source this JVM
   genuinely cannot read; it is not a licence to make readable source unreadable.

   It must be a Namespace OBJECT rather than a symbol: `#::alias{…}` resolves through
   `clojure.core/ns-name`, which takes a namespace or a name it can find, and a made-up symbol is
   neither. `create-ns` is the only way to hand back one — hence a real (empty) namespace per
   alias, which is why the prefix is reserved: `p` in the adopter's `[poly :as p]` must not become
   this JVM's `p`. Nothing reads the namespaces; they exist to be distinct and to stringify."
  [alias]
  (create-ns (symbol (str "fukan.extraction.alias." alias))))

(defn- form-extents
  "The source extent of every COLLECTION form in `file`, at any nesting depth, as
   `{:line :column :end-line :end-column}` — or nil when the file will not read.

   A method's body has no extent in clj-kondo's analysis — there is no var-definition for a
   defmethod and no end position — so it has to be read. Extents rather than start rows, because
   ownership does not change by ROW: two top-level forms share a line whenever someone writes
   `(defmethod …) (register! …)`, and a nested `(do (defmethod …) (f))` has a sibling that no
   row-granular bound can separate from the method beside it. A form's start AND end, in lines
   and columns, is the only thing that says what is inside it.

   ONLY THE POSITIONS SURVIVE — every value the reader builds is discarded — which is what lets
   this read be PERMISSIVE where an accurate one could not be. It reads the ADOPTER's source,
   whose namespaces are not loaded in this JVM, so nothing here can resolve as that file's own
   reader would. Each BINDING buys back one class of ordinary Clojure that would otherwise throw,
   and a throw costs the WHOLE file's method bodies:

     · every ns alias resolves, each to a DISTINCT namespace of its own (`alias-ns`). `::alias/k`
       and `#::alias{…}` are ordinary Clojure and are `Invalid token` to a reader with no such
       alias — and an alias-ACCURATE map is not purchasable at any price, since `poly` in
       `[poly :as p]` is the adopter's namespace and is not loaded here. The keyword comes out
       wrong and nobody looks at it; what nobody may do is let two aliases come out the SAME,
       because `{::a/id 1 ::b/id 2}` is then a duplicate key and the file is lost.
     · every tagged literal is kept AS a `tagged-literal`, INCLUDING the dotted tags a
       `*default-data-reader-fn*` never sees: `#my.ns.Rec{…}` routes to record construction, which
       is a throw while `*read-eval*` is false. Answering for every tag also spares us the built-in
       readers, which throw on a malformed `#inst`. The wrapper is what keeps the value DISTINCT —
       unwrapping to the bare value collides a tagged literal with an equal untagged one, and
       `#{#inst \"2020-01-01\" \"2020-01-01\"}` is then a `Duplicate key` that costs the whole file.
       The walk descends the wrapper through `:form`, so extents inside one are still collected. (Both this and `*alias-map*` are consulted by INVOCATION, so
       a function answers where the docstring says map — hence the pinned reader in deps.edn.)

   The read OPTION answers a different failure, and a worse-behaved one. A throw is loud and lands
   on the fail-safe below; a PARTIAL read is neither. `:read-cond :preserve` keeps every branch of
   a reader conditional, because the question here is which branch has an EXTENT, not which branch
   this JVM would run — and asking a SELECTING read that question is not possible at any feature
   set, since `:allow` takes whichever arm matches first. The miss would be exactly the silent one
   this seam exists to close: clj-kondo analyses a `.cljc` for both languages, so a `defmethod` in
   the unselected arm still emits its marker and its satisfier node, and only its body goes
   missing — or, spliced, its marker falls to the enclosing form's extent and the node claims that
   form's other members instead. Preserving costs the walk two wrappers to descend through, since
   a preserved conditional — and a tagged literal inside one, which the reader suppresses along
   with it, the binding above stopping at the conditional's edge — is no `coll?`; each carries
   what it wraps on `:form`. It costs the refusal below nothing.

   `*read-eval*` stays false, and that one is a REFUSAL rather than a permission: a `#=(…)` form
   drops the file instead of executing it — preserved conditional or not. This reads adopter
   source, so that is a safety property, not a detail.

   nil on ANY read failure, which is the fail-safe answer: a file whose extents are unknown gets
   no attribution at all, so its method bodies are dropped rather than guessed at."
  [file]
  (letfn [(inside [form]
            ;; what the walk descends into. A preserved reader conditional, and a tagged literal
            ;; read inside one, are opaque wrappers rather than collections — their branches hang
            ;; off `:form`, and stopping at either would lose every extent below it.
            (cond
              (reader-conditional? form) [(:form form)]
              (tagged-literal? form)     [(:form form)]
              (coll? form)               form))
          (extents [form]
            (lazy-cat
             (when-let [m (and (coll? form) (meta form))]
               (when (and (:line m) (:end-line m)) [(select-keys m [:line :column :end-line :end-column])]))
             (mapcat extents (inside form))))]
    (try
      (let [rdr (reader-types/indexing-push-back-reader (slurp file))]
        (binding [reader/*alias-map*    alias-ns
                  reader/*data-readers* (fn [tag] #(tagged-literal tag %))
                  reader/*read-eval*    false]
          (loop [acc []]
            (let [form (reader/read {:eof ::eof :read-cond :preserve} rdr)]
              (if (= ::eof form)
                acc
                (recur (into acc (extents form))))))))
      (catch Throwable _ nil))))

(defn- within?
  "Is position `[line col]` inside `extent`? End is EXCLUSIVE — the reader reports `:end-column`
   as one past the form's last character — which is what makes two forms sharing a line disjoint
   rather than overlapping at the seam."
  [{:keys [line column end-line end-column]} [l c]]
  (and (or (< line l) (and (= line l) (<= column c)))
       (or (< l end-line) (and (= l end-line) (< c end-column)))))

(defn- innermost
  "The smallest of `extents` containing `pos`, or nil. Smallest = latest start, since a containing
   form always opens at or before the one it contains."
  [extents pos]
  (->> extents
       (filter #(within? % pos))
       (sort-by (juxt :line :column))
       last))

(defn- attribute-defmethod-bodies
  "clj-kondo attributes a call inside a `defmethod` body to `:from-var nil` — a defmethod is not a
   named var, so its body calls have no enclosing var and call-resolution drops them. Stamp each
   such body call with the id of the SATISFIER that wrote it (`owner-of`, a marker → method-id fn),
   so the call lands on the method rather than being lost.

   Attribution is by CONTAINMENT, in a form extent read from the source. Each method marker is
   located first — the smallest read form containing the marker's own position IS the defmethod
   form, whatever the macro is called locally, because the marker is a direct child of it. A
   from-var-less usage is then that method's body exactly when it lies inside that form.

   Containment is what the two weaker bounds this replaces could not express, and both failed the
   same way. START ROWS alone make `(defmethod …) (register! …)` on one line indistinguishable,
   and make a `(do (defmethod …) (f))` sibling look like the method's body. COLUMN alone cannot
   help either, since a top-level form may be indented. Each admitted a call the method never made
   — and since `call-graph` constrains the callee to no namespace, such a call becomes an
   `ns-depends` edge no var of either namespace justifies, in front of a `Band` law that reads that
   graph globally. An extent has no such gap: a form either contains a position or it does not.

   Nesting is handled rather than excluded. `(do (defmethod …))` is legal and emits a satisfier
   node like any other, and its body is its own extent — so it keeps its calls, and its siblings
   inside the same container keep theirs out of it.

   It fails toward DROPPING rather than inventing. A file that will not read is skipped whole and
   its method bodies are lost — the same thing that already happens to every top-level call in a
   file with no methods in it. An invented edge is the other kind of wrong: indistinguishable from
   a real dependency, with nothing for a reader to question it with.

   WHAT IS DECLINED. A from-var-less usage inside NO method's extent is the other side of the same
   test, and it is a class rather than a leftover: it is every call made outside a method, which is
   where registration-style supply lives (`(register-fact-extractor! …)`, `(extend-type …)`). It
   stays unattributed, and that is DECLINED rather than missed — recognising which such call is a
   fulfilment, and of what surface, is FU-36's question, and nothing here mints a fulfils edge from
   anything but a dispatch marker.

   Usages already carrying a `:from-var`, the defmethod headers themselves, methods whose surface
   is not an extracted function (`owner-of` answers nil — the multimethod is a library's, so the
   whole method is outside the project graph), and files with no defmethods pass through untouched
   — as do ROW-LESS usages: clj-kondo emits macroexpansion artifacts (a `some->`'s injected `->`,
   `if`, `let`) with no source position, and a usage with no row cannot be positionally attributed
   to anything. They carry no `:from-var` either, so `call-graph` drops them regardless; passing
   them through is what keeps positional attribution total."
  [var-usages owner-of]
  (let [markers-by-file (->> var-usages (filter :defmethod) (group-by :filename))
        per-file        (into {}
                              (for [[file markers] markers-by-file
                                    ;; only files that actually carry a method are re-read
                                    :let [exts (form-extents file)]
                                    :when exts]
                                [file (keep (fn [m]
                                              (when-let [e (and (:row m) (:col m)
                                                                (innermost exts [(:row m) (:col m)]))]
                                                [e m]))
                                            markers)]))]
    (mapv (fn [u]
            (if (or (:from-var u) (:defmethod u) (nil? (:row u))
                    (not (contains? per-file (:filename u))))
              u
              (let [pos     [(:row u) (:col u)]
                    holding (filter (fn [[e _]] (within? e pos)) (per-file (:filename u)))
                    [_ m]   (last (sort-by (fn [[e _]] [(:line e) (:column e)]) holding))]
                (if-let [owner (some-> m owner-of)]
                  (assoc u ::owner owner)
                  ;; inside no method's extent: a call made outside any method, which is the
                  ;; registration-supply class this seam declines (FU-36).
                  u))))
          var-usages)))

(defn- call-graph
  "The intra-project call graph as `{caller-id [callee-op-id…]}` over the natural-key ids, from the
   (defmethod-attributed) clj-kondo var-usages. `key->id` maps a `[ns name]` pair to its op id (nil
   if the pair is not an extracted Operation); a usage is an edge iff BOTH endpoints resolve and
   differ (self-calls dropped).

   A caller is either a var — resolved through `key->id` — or a SATISFIER, whose id
   `attribute-defmethod-bodies` stamped on the usage because it has no var to resolve through. A
   `defmulti` extracts as an ordinary Operation, so calls THROUGH it are ordinary edges; calls
   inside its methods' bodies are not its, and belong to the methods."
  [attributed key->id]
  (->> attributed
       (keep (fn [{:keys [from from-var to name] :as u}]
               (when (and to name)
                 (let [c (or (::owner u) (when from-var (key->id [(str from) (str from-var)])))
                       e (key->id [(str to) (str name)])]
                   (when (and c e (not= c e)) [c e])))))
       distinct
       (reduce (fn [m [c e]] (update m c (fnil conj []) e)) {})))

(defn- satisfiers
  "The satisfier descriptors for every `defmethod` marker in `var-usages`, as `{marker →
   {:id :ns :surface :dispatch}}`. The KEY is the marker usage-map itself, so positional
   attribution — which finds a marker, not a row — can look one up directly. (Two markers are
   equal as maps only if they share a file and a position, so they are the same defmethod.)

   A method whose surface is NOT an extracted function is DROPPED: the multimethod belongs to a
   library, so the surface has no node to point at, and the same intra-project rule that keeps
   library calls out of the `:calls` graph keeps its methods out of the fulfilment graph. A
   `Method` with a dangling `:fulfils` would offend its own cardinality law for no reason a reader
   could act on."
  [var-usages key->id]
  (into {}
        (for [m     (filter :defmethod var-usages)
              :let  [sid (key->id [(str (:to m)) (str (:name m))])]
              :when sid
              :let  [impl-ns (str (:from m))]]
          [m {:id       (clj-method/method-id sid (:dispatch-val-str m) impl-ns)
              :ns       impl-ns
              :surface  sid
              :dispatch (:dispatch-val-str m)}])))

(defn extract-roots
  "The engine-agnostic extraction FACTS over the Clojure source under `paths`: `{:roots [[id
   InstanceValue]…]}`. Every extracted entity is a FLAT root with a natural-key id — `\"ns\"` for a
   Module, `\"ns/op\"` for an Operation, `\"ns/op[dispatch]@impl-ns\"` for a satisfier — and
   cross-references are `substrate/Ref`s by that id: a Module's `:child` points at the ids of the
   functions AND methods it owns, a caller's `:calls` at its callees' op ids (resolved by
   `call-graph`), a method's `:fulfils` at its surface's. So the native Cozo build's ONE assembler
   links the whole graph the same way it links authored var-refs (dedup by id), with no post-build
   eid-arithmetic pass.

   Operations carry their DIRECT effects; a defmulti is a polymorphic Operation. A method carries
   none, as it never did: effects are attributed by ENCLOSING VAR (`op-effects` reads the raw
   usages, before positional attribution runs) and a method body has no enclosing var. The build
   stamps stratum provenance at the merge (`stamp-stratum`)."
  [paths]
  (let [{:keys [namespace-definitions var-definitions var-usages]} (analyze paths)
        ops-by-ns    (group-by :ns (filter #(clj-operation/fn-defining (:defined-by %)) var-definitions))
        module-names (distinct (concat (map :name namespace-definitions)
                                       (keys ops-by-ns)))
        op-effs      (clj-effect/op-effects var-usages)
        op-id        (fn [mname nm] (str mname "/" nm))
        by-key       (into {} (for [[mname vs] ops-by-ns, v vs]
                                [[(str mname) (str (:name v))] (op-id mname (:name v))]))
        by-marker    (satisfiers var-usages by-key)
        attributed   (attribute-defmethod-bodies var-usages (comp :id by-marker))
        calls        (call-graph attributed by-key)
        ;; `distinct` because a duplicate `defmethod` in one file is two markers for ONE satisfier
        ;; (the triple is the same), and a namespace must not list it twice among its members
        sats-by-ns   (group-by :ns (distinct (vals by-marker)))
        per-module   (for [mname module-names
                           :let [op-roots (for [v (ops-by-ns mname)
                                                :let [oid  (op-id mname (:name v))
                                                      effs (get op-effs [(str mname) (str (:name v))])]]
                                            [oid (clj-operation/extract-operation v effs (get calls oid))])
                                 sat-roots (for [{:keys [id surface dispatch]} (sats-by-ns (str mname))]
                                             [id (clj-method/extract-method
                                                  id surface dispatch (get calls id))])
                                 members  (concat op-roots sat-roots)]]
                       {:module [(str mname) (clj-module/extract-module mname (mapv first members))]
                        :ops    members})]
    {:roots (vec (concat (map :module per-module)
                         (mapcat :ops per-module)))}))
