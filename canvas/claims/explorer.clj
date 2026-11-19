(ns canvas.claims.explorer
  "What the browser explorer's design commits its modules to. Each Claim's name is the id the
   design record carries for it and its docstring is the record's statement."
  (:require [canvas.vocab.claim :refer [Claim]]
            [canvas.architecture.orchestration.cli :as cli]
            [canvas.architecture.orchestration.infra :as infra]
            [canvas.architecture.orchestration.repl :as repl]
            [canvas.architecture.projection.design :as design]
            [canvas.architecture.projection.view :as view]
            [canvas.architecture.web.explorer :as explorer]))

(Claim view-is-data
  "Which relations nest and which are drawn as edges is stated by the view spec the projection
   is handed, and by nothing else. The projection draws only what that spec names."
  {:about [view/projection-view] :evidence "round"})

(Claim viewer-names-no-vocabulary
  "The view projection and the explorer mention no sort and no relation of any vocabulary; a
   model in a vocabulary fukan does not ship is drawn by the same code."
  {:about [view/projection-view explorer/explorer] :evidence "round"})

(Claim only-the-declared-design
  "A view's selection is the design projection's declared nodes [declared-is-one-definition],
   narrowed by the spec's select when it has one, and every node of a view graph is a node of
   its selection. On a model built with extraction no extracted node and no edge to one
   appears."
  {:about [view/projection-view design/projection-design] :evidence "round"})

(Claim declared-is-one-definition
  "The design projection's declared nodes exclude extracted nodes, and no caller removes them
   from its result."
  {:about [design/projection-design] :evidence "round"})

(Claim visible-is-what-was-opened
  "Below its root level a view graph is what the viewer opened: a node of the selection
   [only-the-declared-design] that has a container in the selection under the nesting relations
   appears only as a direct member of a visible open path, and every direct member of a visible
   open path appears."
  {:about [view/projection-view] :evidence "round"})

(Claim unplaced-is-counted-not-drawn
  "A declared node that the nesting relations of a view spec neither put inside another
   declared node nor give a declared member is unplaced. Unless the spec asks for unplaced
   nodes, none appears in the view graph; the view graph of an unrooted spec counts, by sort,
   the unplaced nodes of its selection, and the count is the same whether or not the spec asked
   to draw them. The view graph of a rooted spec [a-view-may-be-rooted] counts none: every node
   below a root is placed by it. Placement is judged over the whole declared design and not
   over the selection."
  {:about [view/projection-view] :evidence "round"})

(Claim a-view-may-be-rooted
  "A view spec may name one declared node as its root. The root level of its view graph is then
   the root's direct members under the nesting relations that are in the selection, whether or
   not the root itself is; no node that is not below the root appears, and no edge with an end
   that is not below the root is drawn. A root the model does not declare yields a view graph
   with no nodes, no edges and no count, not a refusal."
  {:about [view/projection-view] :evidence "round"})

(Claim edges-are-carried-not-dropped
  "A link edge between two nodes of the selection [only-the-declared-design] is drawn between
   the nearest visible occurrences of its ends whenever those differ, aggregated with a count;
   and every drawn edge carries at least one such declared edge."
  {:about [view/projection-view] :evidence "round"})

(Claim paths-are-entity-ids
  "A node is identified on the wire and in the page's address by the path of entity ids leading
   to it, never by an engine id, so the same address shows the same graph after an unchanged
   model is rebuilt."
  {:about [view/projection-view explorer/explorer] :evidence "round"})

(Claim explorer-holds-nothing
  "The explorer obtains the model by calling the function it was started with, once per
   request, and keeps neither a model nor any viewer's state between requests. Only its caller —
   the cockpit, or the CLI's `explore` verb — knows which model that function yields."
  {:about [explorer/explorer infra/infra-model repl/repl cli/cli] :evidence "round"})

(Claim a-declaration-is-answered-by-entity-id
  "Selecting a node shows its authored declaration: the explorer answers a declaration request,
   which names one node by its entity id, with a DeclarationAnswer — the time the model was
   read for that request, and exactly one of the node's sort and authored form, a rejection of
   a request naming no node, missing when the declared design [declared-is-one-definition] has
   no node with that id, or no-model. An extracted node is missing."
  {:about [explorer/explorer] :evidence "round"})

(Claim request-shape-is-parsed-once
  "The explorer serves three readings — the graph, one node's declaration
   [a-declaration-is-answered-by-entity-id], and the relations a model offers to view by — and
   each is answered in one form: the time the model was read for that request, and exactly one
   tagged alternative, of which no-model is always one. The relations on offer are answered as
   a ChoicesAnswer: those relations, as the view projection's ViewChoices, or no-model. A graph
   request parses to a GraphRequest: tagged view with a view spec and open paths, or tagged
   rejected with the reason — a malformed parameter or a relation name that is not among those
   the model offers to view by. That offer is one value, the view projection's ViewChoices: the
   parse, its rejection and the ChoicesAnswer all read it. Every graph request is answered with
   a GraphAnswer: the time the model was read for that request, and exactly one of the graph,
   that rejection, the engine's refusal of the selection as unanswerable, or no-model. The view
   projection is only called with a parsed spec and paths and checks no shape."
  {:about [explorer/explorer view/projection-view] :evidence "round"})

(Claim web-is-below-orchestration
  "The web subsystem depends on projection, the kernel and the engine only; orchestration depends
   on it and it never on orchestration."
  {:about [explorer/explorer repl/repl view/projection-view] :evidence "round"})
