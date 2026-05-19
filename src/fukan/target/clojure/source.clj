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

(defn extract-symbols
  "Read a Clojure file and return a vector of
     {:kind :function|:data-structure|:function-private :ns <string> :name <string>
      :file <path>}
   records for every top-level def / defn / defn-."
  [path]
  (let [forms (read-forms path)
        ns-name (or (ns-of-forms forms) "")]
    (vec
      (keep (fn [f]
              (when (and (list? f) (symbol? (first f)) (symbol? (second f)))
                (case (str (first f))
                  "def"   {:kind :data-structure
                           :ns ns-name
                           :name (str (second f))
                           :file path}
                  "defn"  {:kind :function
                           :ns ns-name
                           :name (str (second f))
                           :file path}
                  "defn-" {:kind :function-private
                           :ns ns-name
                           :name (str (second f))
                           :file path}
                  nil)))
            forms))))
