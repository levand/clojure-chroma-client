(ns clojure-chroma-client.config)

(defn- truth-str?
  [s]
  (and (string? s) (= "true" (.toLowerCase s))))

(defn getenv
  ([var] (getenv var nil))
  ([var default]
   (or (when-let [val (System/getenv var)]
         (when-not (empty? val) val))
       default)))

(def ^:dynamic *protocol* (getenv "CHROMA_PROTOCOL" "http"))
(def ^:dynamic *host* (getenv "CHROMA_HOST"))
(def ^:dynamic *port* (getenv "CHROMA_PORT" 8000))
(def ^:dynamic *api-version* (getenv "CHROMA_API_VERSION" "v2"))
(def ^:dynamic *api-key* (getenv "CHROMA_API_KEY"))
(def ^:dynamic *timeout* (getenv "CHROMA_TIMEOUT" 10000))
(def ^:dynamic *tenant* (getenv "CHROMA_TENANT" "default_tenant"))
(def ^:dynamic *database* (getenv "CHROMA_DATABASE" "default_database"))
(def ^:dynamic *allow-reset* (truth-str? (getenv "CHROMA_ALLOW_RESET")))

(defn configure
  "Pass a map to set various options for Chroma and how the library
  works. Every option also can be configured by a `CHROMA_*`
  environment variable of the same name.

  Configurable options are:

  - `protocol`: http/https. Default http.
  - `host`: Chroma host. Required, no default.
  - `port`: HTTP port. Default 8000.
  - `api-version`: API version, \"v1\" or \"v2\". Default \"v2\".
  - `timeout`: HTTP timeout in milliseconds. Default 10000.
  - `api-key`: Chroma API key. Required for hosted Chroma, no default.
  - `tenant`: Unique tenant id. Default \"default_tenant\".
  - `database`: Database name. Default \"default_database\".
  - `allow-reset`: Whether it is possible delete all data via the API. Defaults to false."
  [opts]
  (doseq [[key value] opts]
    (let [var-sym (symbol "clojure-chroma-client.config" (str "*" (name key) "*"))
          var (find-var var-sym)]
      (when-not var
        (throw (ex-info (str "Unknown configuration option: " key) {:key key})))
      (alter-var-root var (constantly value)))))
