# clojure-chroma-client

A thin Clojure client for the Chroma vector database, supporting
open-source Chroma as well as Chroma's managed cloud product.

## Rationale

The third-party Java client for Chroma works well, but is generated
from Chroma's OpenAPI specs and uses Chroma's approach of passing
separate lists for IDs, embeddings, metadata.

This is particularly ill-suited for use with Clojure, since Clojure's
data manipulation idioms are all focused around lazy sequences of
complete records.

This library is hand-written to provide Clojure functions that take
and return sequences.

## Setup

Add the git dependency to your `deps.edn` file:

```clojure
{:deps {:git/url "https://github.com/levand/clojure-chroma-client.git"
        :sha "<current-sha>"}}
```

This library is not yet packaged or distributed via Maven. To use from
Leiningen, consider a plugin such as
[lein-git-deps](https://github.com/tobyhede/lein-git-deps).

## Configuration

There are three ways to configure the library.

1. Environment variables, e.g. `CHROMA_HOST=localhost`.
2. Passing a map to the `clojure-chroma-client.config/configure` function,
   e.g. `(configure {:host "localhost"})`.
3. Binding the thread-local vars in the `clojure-chroma-client.config`
   namespace, e.g. `(binding [config/*host* "localhost"] ...)`

Available configuration options are:

- `host` - host of the Chroma server. Required.
- `port` - port of the Chroma server. Default 8000.
- `protocol` - Protocol for the Chroma server. Default "http".
- `timeout` - HTTP timeout for requests, in milliseconds. Default
  10000 (ten seconds.)
- `allow-reset` - Set to "true" to allow calling the `reset` function,
  which will wipe your entire database. Default is false, for safety.

Additional configuration options required when connecting to the Cloud version are:

- `api-key`
- `tenant`
- `database`

## Usage

Public functions functions in the `clojure.chroma-client/api`
namespace are asynchronous and immediately return a promise or future,
which must be dereferenced to obtain the results.

You must currently provide your own embedding vectors, which can be
JVM arrays or Clojure vectors/lists/seqs.

To manipulate collections:

```clojure
;; Create a collection (idempotent, returns existing colls of the same name)
(def my-coll @(api/create-collection "my-collection"))

;; Create a collection with metadata
(api/create-collection "my-collection" :metadata {:key "value"})

;; List collections
@(api/collections)

;; Get an existing collection
(def my-coll @(api/get-collection "my-collection"))

;; Delete a collection
(api/delete-collection my-coll)

```

Pass the return value of `create-collection` or `get-collection` to
functions that manipulate embeddings.


```clojure

;; Add embeddings. Documents and metadata are optional.
(def embeddings [{:id "e1" :embedding [0.0, 0.0, ..., 0.0], :document "<document1>" :metadata {:foo "bar"}}
                 {:id "e2" :embedding [0.5, 0.9, ..., 0.0], :document "<document2>" :metadata {:foo "biz"}}
                 ...])

@(api/add my-coll embeddings :upsert? false)

;; Add a lazy seq of embeddings in batches and (optionally) in parallel.
@(api/add-batches my-coll (my-embedding-generator) :batch-size 32 :parallel 2)

;; Get embeddings.
@(api/get my-coll :ids ["e1" "e2"] :where {:foo {"$eq" "bar"}} :include [:ids :metadatas])

;; Query K nearest neighbors of a single embedding
@(api/query my-coll [0.0, 0.1, ..., 0.9] :include [:ids :distances] :num-results 10)

;; Query in batches
@(api/query-batch my-coll [[0.0, 0.1, ..., 0.9], ...] :include [:ids :distances])

;; Delete embeddings
(api/delete my-coll :ids ["e1"])

;; Update embeddings
(api/update my-coll [{:id "e1" :metadata {:foo "newvalue"}}])

```

By default, `collections` or `get` will return up to 100 items at a
time (or goverend by the `:limit` option. Pass the return value (prior
to dereferencing) to the `page-seq` function to return a lazy sequence
of all results, which fetches additional items on demand.

``` clojure
;; Get first page
(def result (api/get my-coll :limit 50))

(count @result) ;  => 100

;; Get all records
(def all (api/page-seq result))

;; Realizes the whole list, making multiple round trips to Chroma under the hood
(count all) ; => 1000000

```
See source code for other API functions.



