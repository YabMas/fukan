(ns fukan.model.analyzers
  "Analyzer dispatch.
   Each language analyzer registers via defmethod on the analyze multimethod.")

;; -----------------------------------------------------------------------------
;; Schemas

(def ^:schema AnalyzerKey
  [:keyword {:description "Dispatch key for a registered source analyzer (e.g. :clojure, :allium)."}])

;; -----------------------------------------------------------------------------
;; Analyzer dispatch

(defmulti analyze
  "Dispatch a source analyzer by keyword. Each language registers via defmethod."
  {:malli/schema [:=> [:cat :AnalyzerKey :FilePath] :AnalysisResult]}
  (fn [analyzer-key _src-path] analyzer-key))
