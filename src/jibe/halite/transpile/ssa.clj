;; Copyright (c) 2022 Viasat, Inc.
;; Licensed under the MIT license

(ns jibe.halite.transpile.ssa
  "Rewrite halite specs such that their expressions are stored in a single directed graph
  that is reminiscent of the single static assignment (SSA) representation often used
  in compilers."
  (:require [clojure.set :as set]
            [clojure.pprint :as pp]
            [jibe.halite.halite-envs :as halite-envs]
            [jibe.halite.halite-types :as halite-types]
            [jibe.halite.transpile.util :refer [mk-junct]]
            [schema.core :as s]
            [weavejester.dependency :as dep]))

(def ^:private renamed-ops
  ;; TODO: When we support vectors, we'll need to remove this as an alias and have separate implementations, since get* is 1-based for vectors.
  '{mod* mod, get* get, if-value- if-value})

(def ^:private supported-halite-ops
  (into
   '#{dec inc + - * < <= > >= and or not => div mod expt abs = if not= let get valid? refine-to if-value when error
      ;; Introduced by let and rewriting rules to prevent expression pruning and preserve semantics.
      $do!
      ;; These are not available to halite users; they serve as the internal representation of if-value forms.
      $value? $value!}
   (keys renamed-ops)))

(s/defschema NodeId
  (s/constrained s/Symbol #(re-matches #"\$[1-9][0-9]*" (name %))))

(s/defschema SSATerm
  (s/cond-pre
   s/Int
   s/Bool
   s/Symbol
   s/Str
   (s/enum :Unset)))

(s/defschema SSAOp (apply s/enum (disj supported-halite-ops 'let)))

(s/defschema SSAForm
  (s/cond-pre
   SSATerm
   ;;#{SSATerm}
   {:$type halite-types/NamespacedKeyword
    s/Keyword SSATerm}
   [(s/one SSAOp :op) (s/cond-pre NodeId s/Keyword)]))

(s/defschema Node
  [(s/one SSAForm :form) (s/one halite-types/HaliteType :type) (s/optional NodeId :negation)])

(s/defn node-form :- SSAForm
  [node :- Node]
  (first node))

(s/defn node-type :- halite-types/HaliteType
  [node :- Node]
  (second node))

(s/defn child-nodes :- [NodeId]
  "Return the nodes directly referenced by a given node.
  When the node has boolean type, its negation node is NOT included."
  [node :- Node]
  (let [form (node-form node)]
    (cond
      (seq? form) (->> form rest (filter symbol?))
      (map? form) (-> form (dissoc :$type) vals)
      :else [])))

(s/defschema SSAGraph
  "Invariants:

  * No two nodes in dgraph have the same form.
  * form-ids is the inverse of dgraph.
  * next-id is larger than the integer suffix of any key in dgraph.
  * dgraph is acyclic.
  * The nodes with :Boolean type are the nodes that have a neg-id.
  * Every neg-id has a corresponding entry in dgraph."
  {:dgraph {NodeId Node}
   :next-id s/Int
   :form-ids {SSAForm NodeId}})

(def empty-ssa-graph
  {:dgraph {}
   :next-id 1
   :form-ids {}})

(s/defn ^:private index-dgraph-forms :- {SSAForm NodeId}
  [dgraph :- {NodeId Node}]
  (persistent! (reduce-kv (fn [acc id [form]] (assoc! acc form id)) (transient {}) dgraph)))

(s/defn make-ssa-graph :- SSAGraph
  [dgraph :- {NodeId Node}]
  {:dgraph dgraph
   :next-id (or (->> dgraph keys (map #(Integer/parseInt (subs (name %) 1))) sort last) 1)
   :form-ids (index-dgraph-forms dgraph)})

(s/defschema SpecInfo
  "A halite spec, but with all expressions encoded in a single SSA directed graph."
  (assoc halite-envs/SpecInfo
         :ssa-graph SSAGraph
         :constraints [[(s/one s/Str :cname) (s/one NodeId :node)]]))

(s/defn contains-id? :- s/Bool
  [ssa-graph :- SSAGraph id]
  (contains? (:dgraph ssa-graph) id))

(s/defn deref-id :- Node
  "Return the node in ssa-graph referenced by id."
  [{:keys [dgraph] :as ssa-graph} :- SSAGraph id]
  (or (dgraph id)
      (throw (ex-info "BUG! Failed to deref node id." {:id id}))))

(s/defn negated :- NodeId
  "Return the id of the node that represents the negation of the node with given id."
  [ssa-graph :- SSAGraph, id :- NodeId]
  (when-not (contains-id? ssa-graph id)
    (throw (ex-info "BUG! Asked for negation of expression not in ssa graph"
                    {:ssa-graph ssa-graph :id id})))
  (let [node (deref-id ssa-graph id)]
    (when (not= (node-type node) :Boolean)
      (throw (ex-info "BUG! Asked for negation of a non-boolean expression"
                      {:ssa-graph ssa-graph :id id :node node})))
    (when (not= 3 (count node))
      (throw (ex-info "BUG! boolean node without neg-id!" {:ssa-graph ssa-graph :id id :node node})))
    (let [neg-id (nth node 2)]
      (when-not (contains-id? ssa-graph neg-id)
        (throw (ex-info "BUG! ssa-graph does not contain negation"
                        {:ssa-graph ssa-graph :id id :neg-id neg-id})))
      neg-id)))

(s/defschema ReachableNodesOpts
  {(s/optional-key :include-negations?) s/Bool
   (s/optional-key :conditionally?) s/Bool})

(s/defn reachable-nodes :- #{NodeId}
  "Return the set of nodes transitively reachable from the given id. For reachable boolean
  nodes, their negation nodes are included when include-negations? is true.
  When conditionally? is false, the reachability analysis stops at conditional
  forms (if, when). The conditional nodes themselves are included, but their branches are not."
  ([{:keys [ssa-graph constraints] :as spec-info} :- SpecInfo]
   (reduce
    (fn [reachable id]
      (reachable-nodes ssa-graph reachable id {:include-negations? false, :conditionally? true}))
    #{}
    (map second constraints)))
  ([ssa-graph :- SSAGraph, id :- NodeId]
   (reachable-nodes ssa-graph id {}))
  ([ssa-graph :- SSAGraph, id :- NodeId, opts :- ReachableNodesOpts]
   (reachable-nodes ssa-graph #{} id opts))
  ([ssa-graph :- SSAGraph, reached :- #{NodeId}, id :- NodeId, opts :- ReachableNodesOpts]
   (let [{:keys [include-negations? conditionally?]
          :or {include-negations? false, conditionally? true}} opts]
     (loop [ids (transient [id])
            reached (transient reached)]
       (let [n (count ids)]
         (if (= 0 n)
           (persistent! reached)
           (let [next-id (nth ids (dec n)), ids (pop! ids)]
             (if (reached next-id)
               (recur ids reached)
               (let [node (deref-id ssa-graph next-id), form (node-form node), neg-id (when (= :Boolean (node-type node)) (negated ssa-graph next-id))
                     ids (if (and (coll? form) (or conditionally? (not (and (seq? form) (#{'if 'when} (first form))))))
                           (reduce (fn [ids v] (if (symbol? v) (conj! ids v) ids))
                                   ids
                                   (if (map? form)
                                     (vals (dissoc form :$type))
                                     (rest form)))
                           ids)
                     ids (if (and include-negations? neg-id)
                           (conj! ids neg-id)
                           ids)]
                 (recur ids (conj! reached next-id)))))))))))

(s/defn reachable-subgraph :- SSAGraph
  "Return the subgraph reachable from id."
  [{:keys [dgraph next-id] :as ssa-graph} :- SSAGraph, id :- NodeId]
  (let [dgraph' (select-keys (:dgraph ssa-graph) (reachable-nodes ssa-graph id {:include-negations? true}))]
    {:dgraph dgraph'
     :next-id next-id
     :form-ids (index-dgraph-forms dgraph')}))

;;;;;;;;;; Pruning unreachable expressions ;;;;;;;;;;;

;; Expression rewriting in the lowering passes may end up making expression nodes
;; unreachable from the 'roots' of the SSA graph. This shouldn't affect semantics,
;; but it may produce unnecessary let bindings when the graph is turned back into expressions.
;; To avoid this, we prune unreachable expressions from the graph after lowering phases that might
;; introduce them.
;; HOWEVER! When a form is put into SSA, (possibly unreachable) nodes representing the negation of
;; each boolean expression are added, and pruning will remove them! So, don't prune until you're "done".

(s/defn prune-ssa-graph
  "Prune nodes not reachable from the roots. When prune-negations? is true, negation nodes
  that are not actually used in the root expressions are also pruned; otherwise, they are not."
  ([spec-info :- SpecInfo, prune-negations? :- s/Bool]
   (update spec-info :ssa-graph prune-ssa-graph (set (map second (:constraints spec-info))) prune-negations?))
  ([{:keys [dgraph] :as ssa-graph} :- SSAGraph, roots :- #{NodeId}, prune-negations? :- s/Bool]
   (let [reachable (reduce
                    (fn [reachable id]
                      (reachable-nodes ssa-graph reachable id {:include-negations? (not prune-negations?)
                                                               :conditionally? true}))
                    #{}
                    roots)
         dgraph (persistent!
                 (reduce
                  (fn [dgraph id]
                    (cond-> dgraph
                      (not (reachable id)) (dissoc! id)))
                  (transient dgraph)
                  (keys dgraph)))]
     (assoc ssa-graph :dgraph dgraph :form-ids (index-dgraph-forms dgraph)))))

(s/defschema NodeInGraph [(s/one SSAGraph :ssa-graph) (s/one NodeId :id)])

(def DerivResult NodeInGraph)

(s/defn find-form :- (s/maybe NodeId)
  [{:keys [form-ids] :as ssa-graph} :- SSAGraph, ssa-form :- SSAForm]
  (form-ids ssa-form))

;;;;;;;;;;;;;;;; Converting to SSA ;;;;;;;;;;;;;;;;;;;;;;;

(s/defn ^:private insert-node :- NodeInGraph
  [ssa-graph :- SSAGraph, form :- SSAForm, htype :- halite-types/HaliteType]
  (let [id (symbol (str "$" (:next-id ssa-graph)))]
    [(-> ssa-graph
         (update :next-id inc)
         (assoc-in [:dgraph id] [form htype])
         (assoc-in [:form-ids form] id))
     id]))

(s/defn ^:private insert-node-and-negation :- NodeInGraph
  [{:keys [next-id] :as ssa-graph} :- SSAGraph, form :- SSAForm & [neg-form]]
  (let [id (symbol (str "$" next-id))
        neg-id (symbol (str "$" (inc next-id)))
        neg-form (if (some? neg-form) neg-form (list 'not id))]
    [(-> ssa-graph
         (assoc :next-id (+ 2 next-id))
         (assoc-in [:dgraph id] [form :Boolean neg-id])
         (assoc-in [:form-ids form] id)
         (assoc-in [:dgraph neg-id] [neg-form :Boolean id])
         (assoc-in [:form-ids neg-form] neg-id))
     id]))

(declare ensure-node)

(s/defn ^:private ensure-boolean-node :- NodeInGraph
  "Ensure that ssa-form and its negation are present in ssa-graph, and that they refer to one another.
  In the process, we can do some normalization of expressions, so that what would otherwise be distinct but
  equivalent nodes in the graph become the same node.

  In particular, we always rewrite

     (not (= ...))  (not= ..)
     (not (< a b))  (<= b a)
     (not (<= a b)) (< b a)
     (> a b)        (< b a)
     (>= a b)       (<= b a)"
  [ssa-graph :- SSAGraph, ssa-form :- SSAForm]
  (let [op (when (seq? ssa-form) (first ssa-form))]
    (condp = op
      '= (insert-node-and-negation ssa-graph ssa-form (apply list 'not= (rest ssa-form)))

      'not= (let [[ssa-graph id] (ensure-node ssa-graph (apply list '= (rest ssa-form)) :Boolean)]
              [ssa-graph (negated ssa-graph id)])

      'not [ssa-graph (negated ssa-graph (second ssa-form))]

      '< (insert-node-and-negation ssa-graph ssa-form (apply list '<= (reverse (rest ssa-form))))

      '> (ensure-node ssa-graph (apply list '< (reverse (rest ssa-form))) :Boolean)

      '<= (let [[ssa-graph id] (ensure-node ssa-graph (apply list '> (rest ssa-form)) :Boolean)]
            [ssa-graph (negated ssa-graph id)])

      '>= (ensure-node ssa-graph (apply list '<= (reverse (rest ssa-form))) :Boolean)

      (condp = ssa-form
        true (insert-node-and-negation ssa-graph true false)
        false (let [[ssa-graph id] (ensure-node ssa-graph true :Boolean)]
                [ssa-graph (negated ssa-graph id)])

        (insert-node-and-negation ssa-graph ssa-form)))))

(s/defn ^:private ensure-node :- NodeInGraph
  [ssa-graph :- SSAGraph, ssa-form :- SSAForm, htype :- halite-types/HaliteType]
  (if-let [id (find-form ssa-graph ssa-form)]
    (let [node (deref-id ssa-graph id)]
      (if (not= htype (node-type (deref-id ssa-graph id)))
        (throw (ex-info (format "BUG! Tried to add node %s, but that form already recorded as %s" [ssa-form htype] node)
                        {:node node :ssa-graph ssa-graph}))
        [ssa-graph id]))
    (if (= :Boolean htype)
      (ensure-boolean-node ssa-graph ssa-form)
      (insert-node ssa-graph ssa-form htype))))

(s/defn ^:private ensure-node-for-app :- NodeInGraph
  [ssa-graph :- SSAGraph, [op & args :as form] :- SSAForm]
  (let [op (get renamed-ops op op)]
    (ensure-node
     ssa-graph
     (cons op args)
     (cond
       ('#{+ - * div mod expt abs} op) :Integer
       ('#{< <= > >= and or not => = not= valid? $value?} op) :Boolean
       :else (throw (ex-info (format  "BUG! Couldn't determine type of function application for '%s'" op)
                             {:form form}))))))

(declare form-to-ssa)

(s/defschema SSACtx
  {:senv (s/protocol halite-envs/SpecEnv)
   :tenv (s/protocol halite-envs/TypeEnv)
   :env {s/Symbol NodeId}
   :ssa-graph SSAGraph})

(def ^:private no-value-symbols
  "All of these symbols mean :Unset"
  #{'$no-value 'no-value 'no-value-})

(s/defn ^:private let-to-ssa :- NodeInGraph
  [{:keys [ssa-graph] :as ctx} :- SSACtx, [_ bindings body :as form]]
  (let [[ctx bound-ids]
        (reduce
         (fn [[ctx bound-ids] [var-sym subexpr]]
           (let [[ssa-graph id] (form-to-ssa ctx subexpr)
                 htype (node-type (deref-id ssa-graph id))]
             [(assoc ctx
                     :tenv (halite-envs/extend-scope (:tenv ctx) var-sym htype)
                     :env (assoc (:env ctx) var-sym id)
                     :ssa-graph ssa-graph)
              (conj bound-ids id)]))
         [ctx []] (partition 2 bindings))]
    (form-to-ssa ctx (doall (concat (apply list '$do! bound-ids) [body])))))

(s/defn ^:private app-to-ssa :- NodeInGraph
  [ctx :- SSACtx, [op & args :as form]]
  (let [[ssa-graph args] (reduce (fn [[ssa-graph args] arg]
                                   (let [[ssa-graph id] (form-to-ssa (assoc ctx :ssa-graph ssa-graph) arg)]
                                     [ssa-graph (conj args id)]))
                                 [(:ssa-graph ctx) []]
                                 args)]
    (ensure-node-for-app ssa-graph (apply list op args))))

(s/defn ^:private symbol-to-ssa :- NodeInGraph
  [{:keys [ssa-graph tenv env]} :- SSACtx, form]
  (cond
    (contains? no-value-symbols form) (ensure-node ssa-graph :Unset :Unset)
    (contains-id? ssa-graph form) [ssa-graph form]
    (contains? env form) [ssa-graph (env form)]
    :else (let [htype (or (get (halite-envs/scope tenv) form)
                          (throw (ex-info (format "BUG! Undefined: '%s'" form) {:tenv tenv :form form})))]
            (ensure-node ssa-graph form htype))))

(s/defn ^:private if-to-ssa :- NodeInGraph
  [ctx :- SSACtx, [_ pred then else :as form]]
  (let [[ssa-graph pred-id] (form-to-ssa ctx pred)
        [ssa-graph then-id] (form-to-ssa (assoc ctx :ssa-graph ssa-graph) then)
        [ssa-graph else-id] (form-to-ssa (assoc ctx :ssa-graph ssa-graph) else)
        htype (halite-types/meet (node-type (deref-id ssa-graph then-id))
                                 (node-type (deref-id ssa-graph else-id)))]
    (ensure-node ssa-graph (list 'if pred-id then-id else-id) htype)))

(s/defn ^:private when-to-ssa :- NodeInGraph
  [ctx :- SSACtx, [_ pred then :as form]]
  (let [[ssa-graph pred-id] (form-to-ssa ctx pred)
        [ssa-graph then-id] (form-to-ssa (assoc ctx :ssa-graph ssa-graph) then)
        [_ then-type] (deref-id ssa-graph then-id)]
    (ensure-node ssa-graph (list 'when pred-id then-id) (halite-types/meet then-type :Unset))))

(s/defn ^:private if-value-to-ssa :- NodeInGraph
  [{:keys [ssa-graph] :as ctx} :- SSACtx, [_ var-sym then else :as form]]
  (when-not (symbol? var-sym)
    (throw (ex-info "BUG! if-value form's predicate is not a symbol" {:form form})))
  (let [[ssa-graph no-value-id] (ensure-node ssa-graph :Unset :Unset)
        [ssa-graph unguarded-id] (symbol-to-ssa (assoc ctx :ssa-graph ssa-graph) var-sym)
        htype (node-type (deref-id ssa-graph unguarded-id))]
    (cond
      (= htype :Unset)
      (-> ctx
          (assoc :ssa-graph ssa-graph)
          (cond-> (not (contains? no-value-symbols var-sym)) (update :tenv halite-envs/extend-scope var-sym :Unset))
          (update :env assoc var-sym no-value-id)
          (form-to-ssa else))

      (not (halite-types/maybe-type? htype))
      (let [[ssa-graph guard-id] (ensure-node ssa-graph (list '$value? unguarded-id) :Boolean)
            [ssa-graph then-id] (-> ctx (assoc :ssa-graph ssa-graph) (form-to-ssa then))
            [ssa-graph else-id] (-> ctx (assoc :ssa-graph ssa-graph) (form-to-ssa else))
            htype (halite-types/meet (node-type (deref-id ssa-graph then-id))
                                     (node-type (deref-id ssa-graph else-id)))]
        (ensure-node ssa-graph (list 'if guard-id then-id else-id) htype))

      :else
      (let [[ssa-graph guard-id] (ensure-node ssa-graph (list '$value? unguarded-id) :Boolean)
            inner-type (halite-types/no-maybe htype)
            [ssa-graph value-id] (ensure-node ssa-graph (list '$value! unguarded-id) inner-type)
            [ssa-graph then-id] (-> ctx
                                    (assoc :ssa-graph ssa-graph)
                                    (update :tenv halite-envs/extend-scope var-sym inner-type)
                                    (update :env assoc var-sym value-id)
                                    (form-to-ssa (if (= then unguarded-id) value-id then)))
            [ssa-graph else-id] (-> ctx
                                    (assoc :ssa-graph ssa-graph)
                                    (update :tenv halite-envs/extend-scope var-sym :Unset)
                                    (update :env assoc var-sym no-value-id)
                                    (form-to-ssa (if (= else unguarded-id) no-value-id else)))
            htype (halite-types/meet (node-type (deref-id ssa-graph then-id))
                                     (node-type (deref-id ssa-graph else-id)))]
        (ensure-node ssa-graph (list 'if guard-id then-id else-id) htype)))))

(s/defn ^:private get-to-ssa :- NodeInGraph
  [{:keys [ssa-graph senv] :as ctx} :- SSACtx, [_ subexpr var-kw :as form]]
  (let [[ssa-graph id] (form-to-ssa ctx subexpr)
        spec-id (->> id (deref-id ssa-graph) node-type halite-types/spec-id)
        htype (or (->> spec-id (halite-envs/lookup-spec senv) :spec-vars var-kw (halite-envs/halite-type-from-var-type senv))
                  (throw (ex-info (format "BUG! Couldn't determine type of field '%s' of spec '%s'" var-kw spec-id)
                                  {:form form})))]
    (ensure-node ssa-graph (list 'get id var-kw) htype)))

(s/defn ^:private refine-to-to-ssa :- NodeInGraph
  [ctx :- SSACtx, [_ subexpr spec-id :as form]]
  (let [[ssa-graph id] (form-to-ssa ctx subexpr)]
    (when (nil? (halite-envs/lookup-spec (:senv ctx) spec-id))
      (throw (ex-info (format "BUG! Spec '%s' not found" spec-id)
                      {:form form :spec-id spec-id})))
    (ensure-node ssa-graph (list 'refine-to id spec-id) (halite-types/concrete-spec-type spec-id))))

(s/defn ^:private inst-literal-to-ssa :- NodeInGraph
  [ctx :- SSACtx, form]
  (let [spec-id (:$type form)
        [ssa-graph inst] (reduce
                          (fn [[ssa-graph inst] var-kw]
                            (let [[ssa-graph id] (form-to-ssa (assoc ctx :ssa-graph ssa-graph) (get form var-kw))]
                              [ssa-graph (assoc inst var-kw id)]))
                          [(:ssa-graph ctx) {:$type spec-id}]
                          (-> form (dissoc :$type) keys sort))]
    (ensure-node ssa-graph inst (halite-types/concrete-spec-type spec-id))))

(s/defn ^:private do!-to-ssa :- NodeInGraph
  [{:keys [ssa-graph] :as ctx} :- SSACtx, [_do & args :as form]]
  (let [[ssa-graph arg-ids] (reduce (fn [[ssa-graph arg-ids] arg]
                                      (let [[ssa-graph id] (form-to-ssa (assoc ctx :ssa-graph ssa-graph) arg)]
                                        [ssa-graph (conj arg-ids id)]))
                                    [ssa-graph []]
                                    args)
        [_ htype] (deref-id ssa-graph (last arg-ids))]
    (ensure-node ssa-graph (apply list '$do! arg-ids) htype)))

(s/defn ^:private value!-to-ssa :- NodeInGraph
  [{:keys [ssa-graph] :as ctx} :- SSACtx, form]
  (let [[ssa-graph arg-id] (form-to-ssa ctx (second form))
        [subform htype] (deref-id ssa-graph arg-id)]
    (when (= :Unset htype)
      (throw (ex-info "Invalid $value! form: type of inner expression is :Unset"
                      {:ssa-graph ssa-graph :form form :subform subform})))
    (ensure-node ssa-graph (list '$value! arg-id) (halite-types/no-maybe htype))))

(s/defn ^:private error-to-ssa :- NodeInGraph
  [{:keys [ssa-graph] :as ctx} :- SSACtx form]
  (when-not (or (string? (second form))
                (and (symbol? (second form))
                     (contains? ssa-graph (second form))
                     (string? (first (deref-id ssa-graph (second form))))))
    (throw (ex-info "Only string literals currently allowed in error forms" {:form form :ssa-graph ssa-graph})))
  (let [[ssa-graph arg-id] (form-to-ssa ctx (second form))]
    (ensure-node ssa-graph (list 'error arg-id) :Nothing)))

(s/defn replace-in-expr :- NodeInGraph
  [ssa-graph :- SSAGraph, id, replacements :- {NodeId NodeId}]
  (let [node (deref-id ssa-graph id), form (node-form node), htype (node-type node)]
    (if-let [new-id (replacements id)]
      [ssa-graph new-id]
      (cond
        (or (int? form) (boolean? form)) [ssa-graph id]
        (= :Unset form) [ssa-graph id]
        (symbol? form) (if-let [replacement (replacements form)]
                         (ensure-node ssa-graph replacement htype)
                         [ssa-graph id])
        (seq? form) (let [[op & args] form
                          [ssa-graph new-args] (reduce
                                                (fn [[ssa-graph args] term]
                                                  (if (symbol? term)
                                                    (let [[ssa-graph id] (replace-in-expr ssa-graph term replacements)]
                                                      [ssa-graph (conj args id)])
                                                    [ssa-graph (conj args term)]))
                                                [ssa-graph []]
                                                args)]
                      (ensure-node ssa-graph (apply list op new-args) htype))
        (map? form) (let [spec-id (:$type form)
                          [ssa-graph inst] (reduce
                                            (fn [[ssa-graph inst] [var-kw var-id]]
                                              (let [[ssa-graph id] (replace-in-expr ssa-graph var-id replacements)]
                                                [ssa-graph (assoc inst var-kw id)]))
                                            [ssa-graph {:$type (:$type form)}]
                                            (dissoc form :$type))]
                      (ensure-node ssa-graph inst htype))
        :else (throw (ex-info "Unrecognized node form" {:ssa-graph ssa-graph :form form}))))))

(s/defn form-to-ssa :- NodeInGraph
  "Add the SSA representation of form (an arbitrary halite expression) to the given directed graph,
  returning a tuple of the resulting graph and the id of the node for form."
  [{:keys [ssa-graph] :as ctx} :- SSACtx, form]
  (when (nil? ssa-graph)
    (throw (ex-info "WAT" {:ssa-graph ssa-graph :form form})))
  (cond
    (int? form) (ensure-node ssa-graph form :Integer)
    (boolean? form) (ensure-node ssa-graph form :Boolean)
    (string? form) (ensure-node ssa-graph form :String)
    (= :Unset form) (ensure-node ssa-graph form :Unset)
    (symbol? form) (symbol-to-ssa ctx form)
    (seq? form) (let [[op & args] form]
                  (when-not (contains? supported-halite-ops op)
                    (throw (ex-info (format "BUG! Cannot transpile operation '%s'" op) {:form form})))
                  (condp = (get renamed-ops op op)
                    'let (let-to-ssa ctx form)
                    'if (if-to-ssa ctx form)
                    'when (when-to-ssa ctx form)
                    'get (get-to-ssa ctx form)
                    'refine-to (refine-to-to-ssa ctx form)
                    '$do! (do!-to-ssa ctx form)
                    'if-value (if-value-to-ssa ctx form)
                    '$value! (value!-to-ssa ctx form)
                    'error (error-to-ssa ctx form)
                    (app-to-ssa ctx form)))
    (map? form) (inst-literal-to-ssa ctx form)
    :else (throw (ex-info "BUG! Unsupported feature in halite->choco-clj transpilation"
                          {:form form}))))

(s/defn constraint-to-ssa :- [(s/one SSAGraph :ssa-graph), [(s/one s/Str :cname) (s/one NodeId :form)]]
  "TODO: Refactor me as add-constraint, taking and returning SpecInfo."
  [senv :- (s/protocol halite-envs/SpecEnv), tenv :- (s/protocol halite-envs/TypeEnv), ssa-graph :- SSAGraph, [cname constraint-form]]
  (let [[ssa-graph id] (form-to-ssa {:senv senv :tenv tenv :env {} :ssa-graph ssa-graph} constraint-form)]
    [ssa-graph [cname id]]))

(s/defn spec-to-ssa :- SpecInfo
  "Convert a halite spec into an SSA-based representation."
  [senv :- (s/protocol halite-envs/SpecEnv), spec-info :- halite-envs/SpecInfo]
  (let [tenv (halite-envs/type-env-from-spec senv spec-info)
        [ssa-graph constraints]
        ,,(reduce
           (fn [[ssa-graph constraints] constraint]
             (let [[ssa-graph constraint] (constraint-to-ssa senv tenv ssa-graph constraint)]
               [ssa-graph (conj constraints constraint)]))
           [empty-ssa-graph []]
           (:constraints spec-info))]
    (assoc spec-info
           :ssa-graph ssa-graph
           :constraints constraints)))

(s/defschema SpecCtx
  "A map of spec ids to specs in SSA form."
  {halite-types/NamespacedKeyword SpecInfo})

(s/defn as-spec-env :- (s/protocol halite-envs/SpecEnv)
  "Adapt a SpecCtx to a SpecEnv. WARNING! The resulting spec env does NOT return
  specs with meaningful constraints or refinements! Only the spec vars are correct.
  This function is a hack to allow using a spec context to construct type environments.
  It ought to be refactored away in favor of something less likely to lead to errors!"
  [sctx :- SpecCtx]
  (reify halite-envs/SpecEnv
    (lookup-spec* [self spec-id] (some-> sctx spec-id (dissoc :ssa-graph)))))

(defn- spec-ref-from-type [htype]
  (cond
    (and (keyword? htype) (namespace htype)) htype
    (vector? htype) (recur (second htype))
    :else nil))

(defn- spec-refs-from-expr
  [expr]
  (cond
    (integer? expr) #{}
    (boolean? expr) #{}
    (symbol? expr) #{}
    (string? expr) #{}
    (map? expr) (->> (dissoc expr :$type) vals (map spec-refs-from-expr) (apply set/union #{(:$type expr)}))
    (seq? expr) (let [[op & args] expr]
                  (condp = (get renamed-ops op op)
                    'let (let [[bindings body] args]
                           (->> bindings (partition 2) (map (comp spec-refs-from-expr second))
                                (apply set/union (spec-refs-from-expr body))))
                    'get (spec-refs-from-expr (first args))
                    'refine-to (spec-refs-from-expr (first args))
                    (apply set/union (map spec-refs-from-expr args))))
    :else (throw (ex-info "BUG! Can't extract spec refs from form" {:form expr}))))

(defn- spec-refs [{:keys [spec-vars refines-to constraints] :as spec-info}]
  (->> spec-vars
       vals
       (map spec-ref-from-type)
       (remove nil?)
       (concat (keys refines-to))
       set
       (set/union
        (->> constraints (map (comp spec-refs-from-expr second)) (apply set/union)))
       ;; TODO: refinement exprs
       ))

(defn- reachable-specs [senv root-spec-id]
  (loop [specs {}
         next-spec-ids [root-spec-id]]
    (if-let [[spec-id & next-spec-ids] next-spec-ids]
      (if (contains? specs spec-id)
        (recur specs next-spec-ids)
        (let [spec-info (halite-envs/lookup-spec senv spec-id)]
          (recur
           (assoc specs spec-id spec-info)
           (into next-spec-ids (spec-refs spec-info)))))
      specs)))

(s/defn build-spec-ctx :- SpecCtx
  "Starting from root-spec-id and looking up specs from senv, build and return a SpecContext
  containing the root spec and every spec transitively referenced from it, in SSA form."
  [senv :- (s/protocol halite-envs/SpecEnv), root-spec-id :- halite-types/NamespacedKeyword]
  (-> root-spec-id
      (->> (reachable-specs senv))
      (update-vals (partial spec-to-ssa senv))))

(s/defn replace-node :- SpecInfo
  "Replace node-id with replacement-id in spec-info."
  [{:keys [ssa-graph constraints] :as spec-info} :- SpecInfo node-id replacement-id]
  (let [dgraph' (update-vals (:dgraph ssa-graph)
                             (fn [[form htype neg-id]]
                               (cond->
                                [(cond
                                   (seq? form) (map #(if (= % node-id) replacement-id %) form)
                                   (map? form) (update-vals form #(if (= % node-id) replacement-id %))
                                   :else form)
                                 htype]
                                 neg-id (conj neg-id))))]
    (assoc
     spec-info
     :ssa-graph (assoc ssa-graph :dgraph dgraph' :form-ids (index-dgraph-forms dgraph'))
     :constraints (mapv (fn [[cname cid]] [cname (if (= cid node-id) replacement-id cid)]) constraints))))

;;;;;;;; Guards ;;;;;;;;;;;;;;

(s/defschema ^:private Guards
  "For every node, a representation in disjunctive normal form of what must be true for that
  node to get 'evaluated'."
  {NodeId #{#{NodeId}}})

(s/defn ^:private update-guards :- #{#{NodeId}}
  [current :- #{#{NodeId}}, guard :- #{NodeId}]
  (as-> current guards
    ;; Any existing conjuncts that are supersets of guard may be eliminated.
    (->> guards (remove (partial set/subset? guard)) set)

    ;; If any existing conjunct is a subset of guard, guard may be ignored.
    (cond-> guards
      (not (some #(set/subset? % guard) guards)) (conj guard))))

(s/defn ^:private compute-guards* :- Guards
  [ssa-graph :- SSAGraph, current :- #{NodeId}, result :- Guards, id :- NodeId]
  (let [node (deref-id ssa-graph id), form (node-form node), htype (node-type node)
        result (update result id update-guards current)]
    (cond
      (or (integer? form) (boolean? form) (= :Unset form) (string? form)) result
      (symbol? form) result
      (seq? form) (let [[op & args] form]
                    (condp = op
                      'get (compute-guards* ssa-graph current result (first args))
                      'refine-to (compute-guards* ssa-graph current result (first args))
                      'when (let [[pred-id then-id] args
                                  not-pred-id (negated ssa-graph pred-id)]
                              (as-> result result
                                (compute-guards* ssa-graph current result pred-id)
                                (compute-guards* ssa-graph (conj current pred-id) result then-id)))
                      'if (let [[pred-id then-id else-id] args
                                not-pred-id (negated ssa-graph pred-id)]
                            (as-> result result
                              (compute-guards* ssa-graph current result pred-id)
                              (compute-guards* ssa-graph (conj current pred-id) result then-id)
                              (compute-guards* ssa-graph (conj current not-pred-id) result else-id)))
                      (reduce (partial compute-guards* ssa-graph current) result args)))
      (map? form) (->> (dissoc form :$type) vals (reduce (partial compute-guards* ssa-graph current) result))
      :else (throw (ex-info "BUG! Could not compute guards for form"
                            {:id id :form form :ssa-graph ssa-graph :current current :result result})))))

(s/defn ^:private simplify-guards :- #{#{NodeId}}
  [ssa-graph :- SSAGraph, guards :- #{#{NodeId}}]
  ;; guards is in disjunctive normal form... if a conjunct and
  ;; its negation are both in guards, then the whole expression collapses to 'true'
  ;; This is just a heuristic intended primarily to catch when an expression shows up
  ;; in both branches of an if. This problem is in general co-NP-hard.
  ;; https://en.wikipedia.org/wiki/Disjunctive_normal_form
  (let [negated-clauses (->> guards (map #(->> % (map (partial negated ssa-graph)) set)))]
    (if (some (fn [negated-terms] (every? #(contains? guards #{%}) negated-terms)) negated-clauses)
      #{#{}} ; true
      guards)))

(s/defn ^:private ssa-graph->dep-graph :- (s/protocol dep/DependencyGraph)
  "Return a weavejester.dependency graph of the given ssa-graph, where
  the nodes are node ids."
  [ssa-graph :- SSAGraph]
  (->> ssa-graph
       :dgraph
       (reduce
        (fn [g [id d]]
          (reduce #(dep/depend %1 id %2) g (child-nodes d)))
        (dep/graph))))

(s/defn cycle? :- s/Bool
  "Returns true iff the given dgraph contains a cycle. dgraphs with
  cycles are incorrect!"
  [ssa-graph :- SSAGraph]
  (try
    (ssa-graph->dep-graph ssa-graph)
    false
    (catch clojure.lang.ExceptionInfo ex
      (if (-> ex ex-data :reason (= ::dep/circular-dependency))
        true
        (throw ex)))))

(s/defn topo-sort :- [NodeId]
  [ssa-graph :- SSAGraph]
  (dep/topo-sort (ssa-graph->dep-graph ssa-graph)))

(s/defn compute-guards :- Guards
  [ssa-graph :- SSAGraph, roots :- #{NodeId}]
  (-> (reduce
       (partial compute-guards* ssa-graph #{})
       (zipmap (keys (:dgraph ssa-graph)) (repeat #{}))
       roots)
      (update-vals (partial simplify-guards ssa-graph))))

;;;;;;;;; Converting from SSA back into a more readable form ;;;;;;;;

(declare let-bindable-exprs)

(def ^:dynamic *hide-non-halite-ops* true)

(s/defn ^:private form-from-ssa*
  [ssa-graph :- SSAGraph, ordering :- {NodeId s/Int}, guards :- Guards, bound? :- #{s/Symbol}, curr-guard :- #{NodeId}, id]
  (if (bound? id)
    id
    (let [node (or (deref-id ssa-graph id) (throw (ex-info "BUG! Node not found" {:id id :ssa-graph ssa-graph})))
          form (node-form node)]
      (cond
        (or (integer? form) (boolean? form) (string? form)) form
        (= :Unset form) '$no-value
        (symbol? form) (if (bound? form)
                         form
                         (form-from-ssa* ssa-graph ordering guards bound? curr-guard form))
        (seq? form) (cond
                      (= 'get (first form))
                      (list 'get (form-from-ssa* ssa-graph ordering guards bound? curr-guard (second form)) (last form))

                      (= 'refine-to (first form))
                      (list 'refine-to (form-from-ssa* ssa-graph ordering guards bound? curr-guard (second form)) (last form))

                      (and (= '$do! (first form)) *hide-non-halite-ops*)
                      (let [unbound (remove bound? (take (- (count form) 2) (rest form)))
                            [bound? bindings] (reduce
                                               (fn [[bound? bindings] id]
                                                 [(conj bound? id)
                                                  (conj bindings
                                                        id
                                                        (form-from-ssa* ssa-graph ordering guards bound? curr-guard id))])
                                               [bound? []]
                                               unbound)]
                        (-> (form-from-ssa* ssa-graph ordering guards bound? curr-guard (last form))
                            (cond->>
                             (seq unbound) (list 'let bindings))))

                      (and (= '$value! (first form)) *hide-non-halite-ops*)
                      (form-from-ssa* ssa-graph ordering guards bound? curr-guard (second form))

                      (= 'if (first form))
                      (let [[_if pred-id then-id else-id] form
                            [pred] (deref-id ssa-graph pred-id)
                            [then] (deref-id ssa-graph then-id)]
                        (if (and (seq? pred) (= '$value? (first pred)) *hide-non-halite-ops*)
                          (let [value-arg-id (second pred)
                                [value-arg] (deref-id ssa-graph value-arg-id)]
                            (if (and (not (bound? value-arg-id)) (not (symbol? value-arg)))
                              (list 'let [value-arg-id (form-from-ssa* ssa-graph ordering guards bound? curr-guard value-arg-id)]
                                    (form-from-ssa* ssa-graph ordering guards (conj bound? (second pred)) curr-guard id))
                              (list 'if-value
                                    (form-from-ssa* ssa-graph ordering guards bound? curr-guard (second pred))
                                    (let-bindable-exprs ssa-graph ordering guards bound? (conj curr-guard pred-id) then-id)
                                    (let-bindable-exprs ssa-graph ordering guards bound? (conj curr-guard (negated ssa-graph pred-id)) else-id))))
                          (list 'if
                                (form-from-ssa* ssa-graph ordering guards bound? curr-guard pred-id)
                                (let-bindable-exprs ssa-graph ordering guards bound? (conj curr-guard pred-id) then-id)
                                (let-bindable-exprs ssa-graph ordering guards bound? (conj curr-guard (negated ssa-graph pred-id)) else-id))))

                      (= 'when (first form))
                      (let [[_when pred-id then-id] form
                            [pred] (deref-id ssa-graph pred-id)]
                        (list 'when
                              (form-from-ssa* ssa-graph ordering guards bound? curr-guard pred-id)
                              (let-bindable-exprs ssa-graph ordering guards bound? (conj curr-guard pred-id) then-id)))

                      :else
                      (apply list (first form) (map #(form-from-ssa* ssa-graph ordering guards bound? curr-guard %) (rest form))))
        (map? form) (-> form (dissoc :$type) (update-vals #(form-from-ssa* ssa-graph ordering guards bound? curr-guard %)) (assoc :$type (:$type form)))
        :else (throw (ex-info "BUG! Cannot reconstruct form from SSA representation"
                              {:id id :form form :ssa-graph ssa-graph :guards guards :bound? bound? :curr-guard curr-guard}))))))

(def ^:dynamic *elide-top-level-bindings*
  "When true, form-from-ssa elides top-level let bindings, to produce a partial expression.
  This is only to be used for debugging of rewrite rules!"
  false)

(s/defn ^:private let-bindable-exprs
  "We want to avoid as much expression duplication as possible without changing
  semantics. Expressions are side effect free, so we can generally avoid multiple occurrences
  of an expression by introducing a 'let' form higher up in the AST.
  However, expressions can evaluate to runtime errors, and 'if' forms only evaluate one of
  their branches depending on the value of the predicate.
  We need to ensure that our rewritten expressions never evaluate a form when the original
  expressions would not have evaluated it."
  [ssa-graph :- SSAGraph, ordering :- {NodeId s/Int}, guards :- Guards, bound? :- #{s/Symbol}, curr-guard :- #{NodeId}, id]
  (let [subgraph (reachable-subgraph ssa-graph id)
        usage-counts (->> id
                          (reachable-nodes subgraph)
                          (mapv #(deref-id subgraph %))
                          (mapcat child-nodes)
                          frequencies)
        [bound? bindings] (->> subgraph
                               :dgraph
                               (remove
                                (fn [[id [form htype]]]
                                  (or (bound? form) (bound? id)
                                      (integer? form)
                                      (boolean? form)
                                      (= :Unset form)
                                      (<= (get usage-counts id 0) 1)
                                      ;; safe to bind if current guard implies some conjunct
                                      (not (some #(set/superset? curr-guard %1) (guards id)))
                                      ;; don't bind non-halite forms to vars
                                      (and (seq? form)
                                           (contains? #{'$value? '$value!} (first form))))))
                               (map first)
                               (sort-by ordering)
                               (reduce
                                (fn [[bound-set bindings] id]
                                  [(conj bound-set id)
                                   (conj bindings id (form-from-ssa* subgraph ordering guards bound-set curr-guard id))])
                                [bound? []]))]
    (cond->> (form-from-ssa* subgraph ordering guards bound? curr-guard id)
      (and (seq bindings) (not *elide-top-level-bindings*)) (list 'let bindings))))

(defn- next-free-var [scope aliases]
  (loop [n (inc (count aliases))]
    (let [var-sym (symbol (str "v" n))]
      (if (or (contains? scope var-sym) (contains? aliases var-sym))
        (recur (inc n))
        var-sym))))

(defn- normalize-vars
  "Rewrite expr such that all let-bound variables of the form '$<s>'
  are deterministically renamed, leaving the expression otherwise unchanged.
  The normalized variable names are all prefixed with 'v' rather than '$',
  to avoid colliding with SSA node ids.

   (normalize-vars '(let [$43 1, $12 1] (+ $43 $12))) => '(let [v1 1, v2 1] (+ v1 v2))"
  ([scope expr] (normalize-vars scope {} expr))
  ([scope aliases expr]
   (cond
     (or (integer? expr) (boolean? expr) (string? expr) (keyword? expr)) expr
     (symbol? expr) (get aliases expr expr)
     (map? expr) (update-vals expr (partial normalize-vars scope aliases))
     (vector? expr) (mapv (partial normalize-vars scope aliases) expr)
     (set? expr) (set (map (partial normalize-vars scope aliases) expr))
     (seq? expr) (let [[op & args] expr]
                   (case op
                     let (let [[bindings body] args
                               [aliases bindings]
                               ,,(reduce
                                  (fn [[aliases bindings] [var-sym subexpr]]
                                    ;; This assumes that no $<n> binding will ever shadow anything.
                                    ;; The assumption is expected to hold for any expression restored from SSA,
                                    ;; because all SSA let bindings use unique node ids, and a node is only ever bound once.
                                    (let [subexpr (normalize-vars scope aliases subexpr)]
                                      (if (clojure.string/starts-with? (name var-sym) "$")
                                        (let [alias (next-free-var scope aliases)]
                                          [(assoc aliases var-sym alias)
                                           (conj bindings alias subexpr)])
                                        [aliases (conj bindings var-sym subexpr)])))
                                  [aliases []]
                                  (partition 2 bindings))]
                           (list 'let bindings (normalize-vars scope aliases body)))
                     (cons op (map (partial normalize-vars scope aliases) args))))
     :else (throw (ex-info "Couldn't normalize expression" {:expr expr})))))

(s/defn form-from-ssa
  ([{:keys [spec-vars ssa-graph] :as spec-info} :- SpecInfo, id :- NodeId]
   (form-from-ssa (->> spec-vars keys (map symbol) set) ssa-graph id))
  ([scope :- #{s/Symbol}, ssa-graph :- SSAGraph, id :- NodeId]
   (let [ordering (zipmap (topo-sort ssa-graph) (range))]
     (->> id
          (let-bindable-exprs ssa-graph ordering (compute-guards ssa-graph #{id}) scope #{})
          (normalize-vars scope)))))

(s/defn spec-from-ssa :- halite-envs/SpecInfo
  "Convert an SSA spec back into a regular halite spec."
  [spec-info :- SpecInfo]
  (let [{:keys [ssa-graph constraints spec-vars] :as spec-info} (prune-ssa-graph spec-info false)
        scope (->> spec-vars keys (map symbol) set)
        constraint (mk-junct 'and constraints)
        ssa-ctx {:senv (halite-envs/spec-env {})
                 :tenv (halite-envs/type-env {})
                 :env {}
                 :ssa-graph ssa-graph}
        [ssa-graph id] (->> constraints (map second) (mk-junct 'and) (form-to-ssa ssa-ctx))]
    (-> spec-info
        (dissoc :ssa-graph)
        (assoc :constraints [["$all" (form-from-ssa scope ssa-graph id)]]))))

(s/defn make-ssa-ctx :- SSACtx
  [sctx :- SpecCtx, {:keys [ssa-graph] :as spec-info} :- SpecInfo]
  (let [senv (as-spec-env sctx)
        tenv (halite-envs/type-env-from-spec senv (dissoc spec-info :ssa-graph))]
    {:senv senv :tenv tenv :env {} :ssa-graph ssa-graph}))

(s/defn build-spec-env :- (s/protocol halite-envs/SpecEnv)
  [sctx :- SpecCtx]
  (-> sctx (update-vals spec-from-ssa) (halite-envs/spec-env)))

(defn pprint-ssa-graph [ssa-graph]
  (pp/pprint (sort-by #(Integer/parseInt (subs (name (key %)) 1)) (:dgraph ssa-graph))))
