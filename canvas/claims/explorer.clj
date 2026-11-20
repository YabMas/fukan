(ns canvas.claims.explorer
  "What the designs of the explorer, and of looking at a view without one, commit their modules
   to. Each Claim's name is the id the design record carries for it and its docstring is the
   record's statement."
  (:require [canvas.vocab.claim :refer [Claim]]
            [canvas.architecture.cozo.query :as query]
            [canvas.architecture.orchestration.cli :as cli]
            [canvas.architecture.orchestration.infra :as infra]
            [canvas.architecture.orchestration.repl :as repl]
            [canvas.architecture.projection.design :as design]
            [canvas.architecture.projection.drawing :as drawing]
            [canvas.architecture.projection.reference :as reference]
            [canvas.architecture.projection.view :as view]
            [canvas.architecture.web.explorer :as explorer]))

(Claim view-is-data
  "Which relations nest and which are drawn as edges is stated by the view spec the projection
   is handed, and by nothing else. The projection draws only what that spec names."
  {:about [view/projection-view] :evidence "round"})

(Claim viewer-names-no-vocabulary
  "The view projection, the drawing projection, the selection reference and the explorer — its page's markup and script included — mention no sort and no relation of any vocabulary other than the reflection meta-grammar, and no plane label; a model in a vocabulary fukan does not ship, on planes its composition names, is drawn by the same code, and the selections of its declared design are exemplified by the same code."
  {:about [view/projection-view drawing/projection-drawing reference/projection-reference explorer/explorer] :evidence "round"})

(Claim one-universe-per-view
  "A view's universe is the design projection's declared nodes [declared-is-one-definition] when its spec names no plane, and the named nodes the model labels with the plane it names otherwise. A view's selection is its universe narrowed by the spec's select when it has one; every node of a view graph is a node of its selection, and every nesting or link pair it uses has both ends in its universe. So a view of one plane shows no node and no edge of another plane or of the declared design, and a view naming no plane shows no produced node."
  {:about [view/projection-view view/universe design/projection-design] :evidence "round"})

(Claim declared-is-one-definition
  "The design projection's declared nodes exclude every produced node, whatever plane it is on, and no caller removes produced nodes from its result itself; they are the universe of a view that names no plane [one-universe-per-view]."
  {:about [design/projection-design] :evidence "round"})

(Claim visible-is-what-was-opened
  "Below its root level a view graph is what the viewer opened: a node of the selection [one-universe-per-view] that has a container in the selection under the nesting relations appears only as a direct member of a visible open path, and every direct member of a visible open path appears."
  {:about [view/projection-view] :evidence "round"})

(Claim unplaced-is-counted-not-drawn
  "A node of a view's universe [one-universe-per-view] that the nesting relations of its spec neither put inside another node of the universe nor give a member of it is unplaced. Unless the spec asks for unplaced nodes, none appears in the view graph; the view graph of an unrooted spec counts, by sort, the unplaced nodes of its selection, and the count is the same whether or not the spec asked to draw them. The view graph of a rooted spec [a-view-may-be-rooted] counts none: every node below a root is placed by it. Placement is judged over the whole universe and not over the selection."
  {:about [view/projection-view] :evidence "round"})

(Claim a-view-may-be-rooted
  "A view spec may name one node of its universe [one-universe-per-view] as its root. The root level of its view graph is then the root's direct members under the nesting relations that are in the selection, whether or not the root itself is; no node that is not below the root appears, and no edge with an end that is not below the root is drawn. A root its universe does not hold yields a view graph with no nodes, no edges and no count, not a refusal."
  {:about [view/projection-view] :evidence "round"})

(Claim edges-are-carried-not-dropped
  "A link edge between two nodes of the selection [one-universe-per-view] is drawn between the nearest visible occurrences of its ends whenever those differ, aggregated with a count; and every drawn edge carries at least one such edge."
  {:about [view/projection-view] :evidence "round"})

(Claim paths-are-entity-ids
  "A node is identified on the wire and in the page's address by the path of entity ids leading
   to it, never by an engine id, so the same address shows the same graph after an unchanged
   model is rebuilt."
  {:about [view/projection-view explorer/explorer] :evidence "round"})

(Claim explorer-holds-nothing
  "The explorer obtains the model by calling the function it was started with, once per
   request, and keeps neither a model nor any viewer's state between requests. Only its caller —
   the cockpit, or the CLI's `explore` and `view` verbs — knows which model that function yields."
  {:about [explorer/explorer infra/infra-model repl/repl cli/cli] :evidence "round"})

(Claim a-declaration-is-answered-by-entity-id
  "Selecting a node shows its form: the explorer answers a declaration request, which names one node by its entity id and may name a plane, with a DeclarationAnswer — the time the model was read for that request, and exactly one of the node's sort and its form, a rejection of a request naming no node or naming a plane with no value, missing when the universe [one-universe-per-view] of the plane named, or of none when it names none, has no node with that id — as it has none for a plane the model does not hold — or no-model. The declaration answer reads the plane itself, beside the graph request's parse, and builds no view spec."
  {:about [explorer/explorer] :evidence "round"})

(Claim request-shape-is-parsed-once
  "The explorer has an answer for each of four readings — the graph, its drawing, one node's declaration [a-declaration-is-answered-by-entity-id], and the relations and planes a model offers to view by [planes-are-offered] — and each answer has one form: the time the model was read for that request, and exactly one tagged alternative, of which no-model is always one. The offer is answered as a ChoicesAnswer: the view projection's ViewChoices, or no-model. A graph request parses to a GraphRequest: tagged view with a view spec, open paths and a palette — light when the request names none — or tagged rejected with the reason — a malformed parameter, a palette that is not a Theme, a relation name that is not among those the model offers to view by, or a plane the model does not hold. That offer is one value, the view projection's ViewChoices: the parse, its rejection and the ChoicesAnswer all read it. What the explorer's answer returns for a graph request is a GraphAnswer: the time the model was read for that request, and exactly one of the graph, that rejection, the engine's refusal of the selection as unanswerable, or no-model. A graph request whose query string fails percent-decoding in the transport does not reach answer and gets none of them. A drawing request goes through the same parse, and its answer is a DrawingAnswer, the graph's alternative replaced by its drawing in the parsed palette. The view projection is only called with a parsed spec and paths and checks no shape."
  {:about [explorer/explorer view/projection-view] :evidence "round"})

(Claim web-is-below-orchestration
  "The web subsystem depends on projection, the kernel and the engine only; orchestration depends
   on it and it never on orchestration."
  {:about [explorer/explorer repl/repl view/projection-view] :evidence "round"})

(Claim a-view-is-answered-without-a-server
  "The CLI's `view` verb builds the declared design from the spec dirs alone when it is given no --plane, and when it is given one, the model the composition root's registered producers make with them from the source root the CLI defaults to, `src`. It takes no --src, reads only whether --plane was given, and answers its view by the explorer's own answer, handing it the verb's flags, --plane among them, as a graph request's parameters. It builds no view spec of its own, calls the view projection through nothing else, and opens no port. `explore` builds its model the same way: from the spec dirs alone when it is given no --plane, exactly as it does without one today, and with the registered producers over `src` when it is given one. It takes no --src; given a plane the model it built does not hold, it serves nothing and is undecidable, naming the plane. It reads --plane only to choose the build and to check that plane is held, hands the explorer a function yielding that model, and leaves which plane a page draws to the page's own request."
  {:about [cli/cli explorer/explorer] :evidence "round"})

(Claim the-address-is-the-request
  "The page's address carries a view as a graph request's parameters — nest, link, select, open, unplaced, root and plane, under those names and in the encodings the explorer's parse reads — and the page sends them as its address holds them, the plane with its declaration requests too. The ids a viewer dived through to reach the root, which the breadcrumbs step back along, are kept in the address as a trail only the page reads, as the selected path is. An address whose root is a JSON array of ids is replaced before the page asks by one whose root is that array's last id and whose trail is the ids before it. The explorer's `address` writes a graph request's parameters as the query string its transport reads back as those parameters; it takes no view spec."
  {:about [explorer/explorer] :evidence "round"})

(Claim one-drawing-for-page-and-export
  "Where a box sits, how large it is, how an edge is routed and how each is painted — the box a
   viewer selected included — are decided by the drawing projection and by nothing else. A
   Drawing holds a box for every occurrence of the graph it draws, laid inside the box of the
   occurrence that contains it, with the node's label among its marks as text, and marks for
   every edge of that graph. The page shows the SVG the explorer answers and the CLI writes that
   projection's SVG and PNG; the page's script computes no position, creates no box and no edge,
   and says only which box is selected, by a class on that box's group whose paint the SVG
   carries from the Drawing."
  {:about [drawing/projection-drawing explorer/explorer cli/cli] :evidence "round"})

(Claim encoders-decide-nothing
  "The SVG and the PNG of a view are two writings of one Drawing, and each writes every mark of
   its boxes, its edges and its legend. Every position, size, colour, stroke and text a writing
   shows is a value the Drawing states: a text mark states where its text starts, so neither
   encoder measures one, and neither holds a constant that changes how the picture looks."
  {:about [drawing/projection-drawing] :evidence "round"})

(Claim a-drawing-is-a-function-of-its-graph
  "Drawing a view graph reads the graph and the palette asked for and nothing else — no model,
   no font, no clock — so the same graph yields the same Drawing on any machine."
  {:about [drawing/projection-drawing] :evidence "round"})

(Claim an-engine-refusal-is-a-sentence
  "A query the engine refuses leaves the query compiler's `q` as an exception whose message is
   plain text: no terminal escape code and no generated script, the engine's error code named.
   A call to a rule that does not exist names the clause that made it, and says to write
   `or-join` when that clause is headed `or`. An unsafe negation names the negated clauses and
   says that a positive clause must bind each of their variables first."
  {:about [query/cozo-query] :evidence "round"})

(Claim the-reference-ran-what-it-prints
  "The selection reference for a model states the clause forms a selection may use, the
   attributes its declared nodes carry, and that a view draws a link edge only between two
   selected nodes; and it holds a worked selection of each of four kinds — one sort, a union of
   sorts, a grouping with its members, nodes by id prefix with their members — built from that
   model's own sorts, relations and ids. Where the model cannot supply one, the reference
   says in that selection's place that no such example exists for this model.
   Every selection it prints was evaluated against that model to print it and is shown with the
   number of nodes it selected."
  {:about [reference/projection-reference] :evidence "round"})

(Claim the-reference-is-held-by-a-test
  "A test prints the selection reference for a model that declares at least two sorts and a
   relation with stored edges. It fails unless the text holds a worked selection of each of the
   four kinds — one sort, a union of sorts, a grouping with its members, nodes by id prefix
   with their members — and it reads every selection back out of the text and evaluates each
   through the selection engine against that model, failing on one the engine refuses."
  {:about [reference/projection-reference] :evidence "round"})

(Claim planes-are-offered
  "The choices a model offers to view by name, beside the binary relations its grammar declares, the planes it holds, each with how many named nodes carry its label; a relation's edge count is its stored edges whose two ends lie in one universe."
  {:about [view/projection-view explorer/explorer] :evidence "round"})

(Claim the-reference-domain-is-drawn
  "A test builds the reference shop and the web library it uses with the project's producers and asks the explorer for a view of the domain plane nesting by declares-shape, part and attribute and linking by draws-on and entity-references. It asserts the root level is exactly the shop and web vocabularies, each opening onto the shapes it declares and those onto their attributes; a draws-on edge from shop to web and a reference edge from login to web.session; and that no node or edge end of the graph is outside the domain plane. It asserts the same model asked with no plane gives the declared design's graph. Run in reference/shop with --spec-dirs design, where web is not under `src` and is read from the classpath, `fukan.cli view --plane domain` nesting and linking as that test does prints a graph whose root level is exactly the shop and web vocabularies, with the draws-on edge from shop to web and the reference edge from login to web.session, and with --out writes the PNG there and the SVG beside it. Run there with --plane domain, `fukan.cli explore` serves an explorer that, asked for the domain plane with the same nest and link parameters, answers that same graph: both verbs build one model the same way and answer through the explorer's own answer."
  {:about [view/projection-view explorer/explorer cli/cli] :evidence "round"})

(Claim declared-only-descriptions-are-restated
  "The same change restates, under canvas/architecture, every description of the view projection, the explorer, the CLI and the cockpit's explore that names the declared design as the only thing a view draws or the explorer serves — view-graph's graph 'over the declared nodes' and the explorer module's 'Serve the declared design' among them — so that it names a view's universe [one-universe-per-view]; and nido:design:check is green on that change."
  {:about [view/projection-view explorer/explorer cli/cli repl/repl] :evidence "round"})
