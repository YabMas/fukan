(ns fukan.utils.files
  "File system utility schemas and helpers.")

(def ^:schema FilePath
  [:and {:description "Filesystem path: forward-slash separated, non-empty. May be absolute (/...) or project-relative (src/...)."}
   [:string {:min 1}]
   [:re {:error/message "must be a valid file path (forward-slash separated, no backslashes, no leading/trailing whitespace)"}
    #"^[^\s\\].*[^\s]$"]])
