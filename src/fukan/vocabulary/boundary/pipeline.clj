(ns fukan.vocabulary.boundary.pipeline
  "Source walk + parse + analyze for .boundary files. Mirrors
   fukan.vocabulary.allium.pipeline. Runs after the Allium pipeline —
   takes an existing model (with Allium content) and enriches it."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [fukan.libs.boundary.parser :as parser]
            [fukan.libs.coordinate :as coord]
            [fukan.vocabulary.boundary.analyzer :as analyzer]
            [fukan.vocabulary.boundary.tags :as tags]
            [fukan.model.build :as build]))

(defn- find-boundary-files
  "Recursively walk `root` and return absolute paths to all .boundary files,
   sorted for deterministic load order."
  [root]
  (->> (file-seq (io/file root))
       (filter #(.isFile %))
       (filter #(str/ends-with? (.getName %) ".boundary"))
       (map #(.getCanonicalPath %))
       sort))

(defn- coordinate-of
  "Coord = relative path from source root, minus .boundary extension."
  [root abs-path]
  (let [root-canonical (.getCanonicalPath (io/file root))
        prefix         (str root-canonical "/")]
    (-> abs-path
        (cond-> (str/starts-with? abs-path prefix)
                (subs (count prefix)))
        (str/replace-first #"\.boundary$" ""))))

(defn- register-boundary-tags [model]
  (reduce build/add-tag-definition model tags/boundary-tag-definitions))


(defn- extract-use-aliases
  [coord ast]
  (->> (:declarations ast)
       (filter #(= :use (:type %)))
       (map (fn [{:keys [alias path]}]
              [alias (coord/canonicalise-path coord path)]))
       (into {})))

(defn load-source
  "Walk source-root, parse every .boundary file, analyze each. Takes the
   Allium-produced `model` as input and returns the enriched model.

   - Registers Boundary tag-definitions on the model.
   - For each .boundary file: parses, computes coord + use-aliases,
     calls analyzer/analyze-file."
  [model source-root]
  (let [files (find-boundary-files source-root)
        m0    (register-boundary-tags model)]
    (reduce (fn [m f]
              (let [coord   (coordinate-of source-root f)
                    ast     (parser/parse-file f)
                    aliases (extract-use-aliases coord ast)]
                (analyzer/analyze-file m ast coord aliases)))
            m0
            files)))
