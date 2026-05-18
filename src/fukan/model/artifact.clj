(ns fukan.model.artifact
  "V0 projection vocabulary — Artifact ontology and projection_kind enum
   (MODEL.md §7.2–§7.4).

   V0 commits one category: Code, with two leaf cases — Function and
   DataStructure. Infra and Documentation cases come back with their producing
   analyzers (§10.1, §10.5).

   Identity per §7.3: (language, qualified-name). source-location is
   non-identity.")

(def projection-kinds
  #{:projection-kind/rule :projection-kind/operation
    :projection-kind/invariant :projection-kind/schema
    :projection-kind/test})

(defn make-code-function
  ([language qualified-name] (make-code-function language qualified-name nil))
  ([language qualified-name source-location]
   (cond-> {:case :artifact/code, :language language
            :sub {:case :code/function, :qualified-name qualified-name}}
     source-location (assoc-in [:sub :source-location] source-location))))

(defn make-code-data-structure
  ([language qualified-name] (make-code-data-structure language qualified-name nil))
  ([language qualified-name source-location]
   (cond-> {:case :artifact/code, :language language
            :sub {:case :code/data-structure, :qualified-name qualified-name}}
     source-location (assoc-in [:sub :source-location] source-location))))

(defn artifact-identity
  "(case-discriminator, language, qualified-name) per §7.3. Comparators
   dispatch on case (per P3) — different cases get different identity tuples."
  [artifact]
  [(get-in artifact [:sub :case])
   (:language artifact)
   (get-in artifact [:sub :qualified-name])])

(def SourceLocation
  [:map [:file :string] [:line {:optional true} :int]])

(def Artifact
  [:multi {:dispatch :case}
   [:artifact/code
    [:map
     [:case [:= :artifact/code]]
     [:language :string]
     [:sub
      [:multi {:dispatch :case}
       [:code/function
        [:map [:case [:= :code/function]]
         [:qualified-name :string]
         [:source-location {:optional true} SourceLocation]]]
       [:code/data-structure
        [:map [:case [:= :code/data-structure]]
         [:qualified-name :string]
         [:source-location {:optional true} SourceLocation]]]]]]]])
