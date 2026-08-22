(ns clojure-chroma-client.api
  "Chroma API. All API functions return a derefable promise. If the
  value of the promise is an exception, it will be thrown upon deref.
  
  Supports both v1 and v2 APIs. Set *api-version* to \"v1\" or \"v2\" (default)."
  (:require [clojure-chroma-client.config :as config]
            [org.httpkit.client :as http]
            [clj-json.core :as json]
            [clojure.set :as set]
            [clojure.string :as str])
  (:refer-clojure :exclude [update count get]))

(defrecord WrappingPromise [delegate f]
  clojure.lang.IDeref
  (deref [_] (f (deref delegate)))
  clojure.lang.IBlockingDeref
  (deref [_ timeout-ms timeout-val]
    (let [v (deref delegate timeout-ms timeout-val)]
      (if (= timeout-val v)
        v
        (f v))))
  clojure.lang.IPending
  (isRealized [this] (realized? delegate))
  clojure.lang.IFn
  (invoke [this val]
    (delegate val)))

(prefer-method print-method clojure.lang.IDeref clojure.lang.IRecord)

(alter-var-root #'json/*coercions* merge
                {(class (make-array Double/TYPE 1)) seq
                 (class (make-array Double 1)) seq})

(defn- base-url
  "Build base URL for API requests."
  []
  (str config/*protocol* "://" config/*host* ":" config/*port*))

(defn- url
  "Build full URL for API path.
   v1: /api/v1/{path}?tenant=X&database=Y
   v2: /api/v2/tenants/{tenant}/databases/{database}/{path}"
  [path]
  (if (= "v2" config/*api-version*)
    (str (base-url) "/api/v2/tenants/" config/*tenant* "/databases/" config/*database* "/" path)
    (str (base-url) "/api/v1/" path)))

(defn- global-url
  "Build URL for global endpoints (version, heartbeat, reset) that don't need tenant/database."
  [path]
  (str (base-url) "/api/" config/*api-version* "/" path))

(defn- json-body
  [resp]
  (json/parse-string (:body resp) true))

(defn- clean-response
  [resp]
  (if (get-in resp [:opts :headers "x-chroma-token"])
    (assoc-in resp [:opts :headers "x-chroma-token"] "ck-XXXXXXX")
    resp))

(defn- throw-on-error
  [resp]
  (cond
    (:error resp)
    (throw (ex-info "HTTP Error" {:response (clean-response resp)}))
    (not (<= 200 (:status resp) 299))
    (throw (ex-info "Unsuccessful HTTP status code" {:resp (clean-response resp)
                                                     :code (:status resp)
                                                     :body (:body resp)}))))

(defn- request
  "Make an HTTP request to the Chroma API.
   For v1: adds tenant/database as query params.
   For v2: tenant/database are already in the URL path."
  [method path params body resp-fn & {:keys [global?] :or {global? false}}]
  (let [;; For v1, add tenant/database to query params; for v2 they're in the path
        params (if (and (= "v1" config/*api-version*) (not global?))
                 (assoc params
                        :tenant config/*tenant*
                        :database config/*database*)
                 params)
        headers (cond-> {"Content-Type" "application/json"}
                  config/*api-key* (assoc "x-chroma-token" config/*api-key*))
        target-url (if global? (global-url path) (url path))
        ret (http/request
             {:timeout config/*timeout*
              :headers headers
              :method method
              :url target-url
              :query-params (when (seq params) params)
              :body (when body (json/generate-string body))})]
    (->WrappingPromise ret (fn [resp]
                             (throw-on-error resp)
                             (resp-fn (json/parse-string (:body resp) true))))))

(defn version
  "The version of Chroma as reported by the server."
  []
  (request :get "version" {} nil identity :global? true))

(defn reset
  "Reset the entire database. Forbidden unless
  clojure-chroma-client/*allow-reset* is set. Does not work on Chroma
  Cloud."
  []
  (when-not config/*allow-reset* (throw (ex-info "Reset is not enabled, set *allow-reset*." {})))
  (request :post "reset" {} nil identity :global? true))

(defn heartbeat
  "Check that the Chroma server is active and responding."
  []
  (request :get "heartbeat" {} nil identity :global? true))

(defn- with-page-metadata
  [records f & args]
  (let [options (last args)]
    (if (< (clojure.core/count records) (:limit options))
      records
      (let [next-options (clojure.core/update options :offset + (:limit options))
            next-page-fn #(apply f (concat (butlast args) [next-options]))]
        (with-meta records {::next-page next-page-fn})))))

(defn page-seq
  "Return a lazy sequence formed by fetching additional pages of
  results, when ::next-page metadata is present on the results data
  structure."
  [promise]
  (lazy-seq
   (let [records @promise]
     (if-let [next (::next-page (meta records))]
       (concat records (page-seq (next)))
       records))))

(defn collections
  "List collections.

  Returns only `limit` results: use `offset` to page through results.

  Alternatively, if more pages are available, the returned data will
  have a ::next-page metadata entry, the value of which which is a
  zero-arg function to call `collections` again for the next page of
  data."
  [& {:keys [offset limit]
      :or {offset 0 limit 100}
      :as options}]
  (let [opts {:limit limit
              :offset offset}]
    (request :get "collections" opts nil
             (fn [result]
               (with-page-metadata result collections opts)))))

(defn count-collections
  "Total number of collections"
  []
  (request :get "count_collections" {} nil identity))

(def ^:private collection-metadata-defaults
  {"hnsw:batch_size" 100
   "hnsw:sync_threshold" 1000
   "hnsw:space" "l2"
   "hnsw:search_ef" 10
   "hnsw:construction_ef" 100
   "hnsw:M" 16
   "hnsw:num_threads" 4
   "hnsw:resize_factor" 1.2})

(defn create-collection
  "Idempotently create a new collection.
   Note: In v2, metadata cannot be empty - defaults are always applied."
  [name & {:keys [metadata configuration]
           :or {configuration {}}
           :as options}]
  (let [final-metadata (merge collection-metadata-defaults metadata)
        ;; v2 requires non-empty metadata
        final-metadata (if (empty? final-metadata)
                         {"created-by" "clojure-chroma-client"}
                         final-metadata)]
    (request :post "collections"
             {}
             {:name name
              :get_or_create true
              :configuration configuration
              :metadata final-metadata}
             identity)))

(defn get-collection
  "Find and return collection"
  [name]
  (request :get (str "collections/" name) {} nil identity))

(defn update-collection
  "Update a collection's name and/or metadata."
  [collection & {:keys [name metadata]
                 :as options}]
  (when-not (or name metadata)
    (throw (ex-info "Either name or metadata must be provided" {})))
  (request :put (str "collections/" (:id collection))
           {}
           {:new_name name
            :new_metadata metadata}
           identity))

(defn delete-collection
  "Delete a collection"
  [collection]
  (request :delete (str "collections/" (:name collection)) {} {} identity))

(def plural
  {:id :ids
   :metadata :metadatas
   :document :documents
   :embedding :embeddings
   :distance :distances})

(def singular (set/map-invert plural))

(defn- to-cols
  "Convert from seq of maps to a map of keys to seqs. Omits seqs of all
  nil."
  [s cols]
  (into {}
        (map (fn [col valseq]
               (when (some identity valseq)
                 [col valseq]))
             (map plural cols)
             (map (fn [col] (map #(clojure.core/get % col) s)) cols))))

(defn add
  "Add the given embedding records to a collection."
  [collection embeddings & {:keys [upsert?]
                            :or {upsert? false}
                            :as options}]
  (let [url (str "collections/" (:id collection) (if upsert? "/upsert" "/add"))
        records (to-cols embeddings [:id :metadata :document :embedding])]
    (request :post url {} records identity)))

(defn add-batches
  "Add the given embedding records to a collection in
  batches, using the specified level of parallelism."
  [collection embeddings & {:keys [upsert? batch-size parallel]
                            :or {upsert? false
                                 parallel 1
                                 batch-size 32}
                            :as options}]
  (let [queue (atom (seque parallel (partition-all batch-size embeddings)))
        consumer (fn []
                   (when-let [batch (ffirst (swap-vals! queue next))]
                     @(add collection batch :upsert? upsert?)
                     (recur)))]
    (future
      (doseq [fut (repeatedly parallel #(future-call consumer))]
        @fut))))

(defn update
  "Update one or more embedding records"
  [collection embeddings]
  (let [records (to-cols embeddings [:id :metadata :document :embedding])]
    (request :post (str "collections/" (:id collection) "/update") {} records identity)))

(defn count
  "Return the number of embeddings in a collection"
  [collection]
  (request :get (str "collections/" (:id collection) "/count") {} {} identity))

(defn- to-rows
  "Convert from a map of keys to seqs to a seq of maps."
  [m cols]
  (let [newcols (map singular cols)]
    (apply map (fn [& vals]
                 (zipmap newcols vals))
           (vals (select-keys m cols)))))

(defn- normalize-where
  "Normalize where clause for v2 API compatibility.
   v2 API requires $and for multiple top-level conditions.
   {:type \"note\" :project-id \"foo\"} becomes
   {:$and [{:type \"note\"} {:project-id \"foo\"}]}"
  [where]
  (cond
    ;; nil or empty - pass through
    (or (nil? where) (empty? where))
    where
    ;; Already has $and or $or - pass through
    (or (contains? where :$and) (contains? where :$or))
    where
    ;; Single key - pass through as-is
    (= 1 (count where))
    where
    ;; v1 API doesn't need normalization
    (= "v1" config/*api-version*)
    where
    ;; Multiple keys in v2 - wrap in $and
    :else
    {:$and (mapv (fn [[k v]] {k v}) where)}))

(defn get
  "Get embeddings from a collection.

  Options are:

    - `ids`: Only return the specified IDs.
    - `where`: Only return records with matching metadata fields, using
    Chroma's filtering syntax.
    - `where-document`: Only return records with a fulltext document
    match, using Chroma's filtering syntax.

    - `include` is an optional set indicating which parts of an
  embedding to return. Default is #{:metadatas :documents}

    - `limit` and `offset`: returns only `limit` results: use `offset`
  to page through results.

  Alternatively, if more pages are available, the returned data will
  have a ::next-page metadata entry, the value of which which is a
  zero-arg function to call `get` again for the next page of data."
  [collection & {:keys [where ids where-document include offset limit]
                 :or {include #{:metadatas :documents}
                      offset 0
                      limit 100}
                 :as options}]
  (let [opts {:where where
              :ids ids
              :where-document where-document
              :include include
              :offset offset
              :limit limit}
        ;; Normalize where clause for v2 API (requires $and for multiple conditions)
        normalized-where (normalize-where where)
        body {:where normalized-where
              :ids ids
              :include include
              :where_document where-document}
        params (select-keys opts [:limit :offset])
        url (str "collections/" (:id collection) "/get")
        fields (conj include :ids)]
    (request :post url params body
             (fn [result]
               (let [rows (to-rows result fields)]
                 (with-page-metadata rows get collection opts))))))

(defn delete
  "Delete embeddings. Options are:

   - `ids`: Only return the specified IDs.
   - `where`: Only return records with matching metadata fields, using
   Chroma's filtering syntax.
   - `where-document`: Only return records with a fulltext document
   match, using Chroma's filtering syntax.

  At least one of these options is required (this function should not
  be used to delete all records in a collection.)
  "
  [collection & {:keys [where ids where-document]
                 :as options}]
  (when-not (or where ids where-document)
    (throw (ex-info "Delete requires `ids`, `where` or `where-document`" {})))
  (let [body (-> options
                 (assoc :where (normalize-where where))
                 (assoc :where_document where-document)
                 (dissoc :where-document))]
    (request :post (str "collections/" (:id collection) "/delete")
             {} body identity)))

(defn- query-results
  [result cols]
  (apply map (fn [& valseqs]
               (to-rows (zipmap cols valseqs) cols))
         (vals (select-keys result cols))))

(defn query-batch
  "Similar to `query`, but takes a seq of query embeddings "
  [collection query-embeddings & {:keys [where where-document include num-results]
                                  :or {num-results 10
                                       include #{:documents :distances :metadatas}}
                                  :as options}]
  (let [body {:query_embeddings query-embeddings
              :n_results num-results
              :where (normalize-where where)
              :where_document where-document
              :include include}
        url (str "collections/" (:id collection) "/query")
        fields (conj include :ids)]
    (request :post url {} body #(query-results % fields))))

(defn query
  "Perform a KNN search based on a query vector, returning results
  ordered by increasing distance.

  `filter` and `include` options are the same as in `get`.

  `num-results` controls the number of matches returned."
  [collection query-embedding & {:keys [where include num-results]
                                 :or {num-results 10
                                      include #{:documents :distances :metadatas}}
                                 :as options}]
  (->WrappingPromise (query-batch collection [query-embedding] options) first))

;; ============================================================================
;; v2 API: Hybrid Search
;; ============================================================================

(defn search
  "Perform hybrid search combining vector similarity, metadata filtering,
  and full-text search (v2 API only).

  NOTE: Chroma Cloud only - not available in self-hosted/local deployments.
  Local executor returns 'Not implemented for local executor'.

  `searches` is a vector of search specifications, each containing:
    - :filter {:query-ids [...] :where {...}}  - Filter criteria
    - :rank {...}                              - Ranking configuration
    - :select {:keys [...]}                    - Fields to return
    - :limit {:limit n :offset m}              - Pagination
    - :group-by {:keys [...] :aggregate {...}} - Grouping

  Example:
    (search collection
      [{:filter {:where {:type \"note\"}}
        :limit {:limit 10}
        :select {:keys [\"documents\" \"metadatas\"]}}])"
  [collection searches]
  (let [;; Transform clojure-style keys to API format
        transform-search (fn [s]
                           (cond-> {}
                             (:filter s)
                             (assoc :filter
                                    (cond-> {}
                                      (get-in s [:filter :query-ids])
                                      (assoc :query_ids (get-in s [:filter :query-ids]))
                                      (get-in s [:filter :where])
                                      (assoc :where_clause (normalize-where (get-in s [:filter :where])))))
                             (:rank s) (assoc :rank (:rank s))
                             (:select s) (assoc :select (:select s))
                             (:limit s) (assoc :limit (:limit s))
                             (:group-by s) (assoc :group_by (:group-by s))))
        body {:searches (mapv transform-search searches)}
        url (str "collections/" (:id collection) "/search")]
    (request :post url {} body identity)))

;; ============================================================================
;; v2 API: Collection Fork
;; ============================================================================

(defn fork-collection
  "Fork (copy) a collection to a new collection with the given name (v2 API only).

  NOTE: Chroma Cloud only - not available in self-hosted/local deployments.
  Local executor returns 'Collection forking is unsupported for local chroma'.

  Returns the newly created collection.

  Example:
    (fork-collection my-collection \"my-collection-backup\")"
  [collection new-name]
  (let [url (str "collections/" (:id collection) "/fork")
        body {:new_name new-name}]
    (request :post url {} body identity)))
