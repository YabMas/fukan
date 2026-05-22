(ns fukan.libs.coordinate
  "Shared utilities for canonicalising .allium / .boundary file coordinates.

   Replaces the three near-identical helpers that previously lived in
   vocabulary/allium/pipeline, vocabulary/boundary/pipeline, and
   vocabulary/boundary/analyzer (Plan 3b carry-forward)."
  (:require [clojure.string :as str]))

(defn- strip-extension [raw-path]
  (cond
    (str/ends-with? raw-path ".allium")
    (subs raw-path 0 (- (count raw-path) 7))
    (str/ends-with? raw-path ".boundary")
    (subs raw-path 0 (- (count raw-path) 9))
    :else raw-path))

(defn- host-dir [host-coord]
  (let [idx (.lastIndexOf ^String host-coord "/")]
    (if (neg? idx) "" (subs host-coord 0 idx))))

(defn- parent-dir
  "Return the parent of `dir` (a slash-separated path with no trailing slash),
   or \"\" if dir is empty or has no parent."
  [dir]
  (let [up-idx (.lastIndexOf ^String dir "/")]
    (if (neg? up-idx) "" (subs dir 0 up-idx))))

(defn canonicalise-path
  "Resolve a path (`use`/`contains:` raw value) to a root-relative coord
   (no extension). `host-coord` is the file's own coord (root-relative,
   without extension); used for resolving `./` and `../`.

   - `foo.allium` / `foo.boundary` → `foo`
   - `./a.allium` from host `src/h` → `src/a`
   - `../a.allium` from host `src/sub/h` → `src/a`
   - `../../a.allium` from host `src/x/y/h` → `src/a`  (chained `../`)
   - bare paths (no `./` or `../`): treated as root-relative; only the
     extension is stripped."
  [host-coord raw-path]
  (let [no-ext (strip-extension raw-path)
        hd     (host-dir host-coord)]
    (cond
      (str/starts-with? no-ext "./")
      (let [tail (subs no-ext 2)]
        (if (empty? hd) tail (str hd "/" tail)))

      (str/starts-with? no-ext "../")
      ;; Consume each leading `../` by walking up one level on the host dir.
      (loop [tail   no-ext
             parent hd]
        (if (str/starts-with? tail "../")
          (recur (subs tail 3) (parent-dir parent))
          (if (empty? parent) tail (str parent "/" tail))))

      :else no-ext)))
