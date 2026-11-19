# web — a vocabulary shared between systems

How a visit to a site is handled: requests and the sessions they belong to. It is a domain in
its own right, with its own language, and it does not know what any site is about. A system
uses it as a library (`reference/shop` does) and refers to its entities by their one name.

Its names carry a longer namespace (`:web.request/path`, `:web.session/id`) because it is
meant to be shared: a name has one owner, across every system that uses it.

It publishes its views in the file where its vocabulary is declared (`web.vocabulary`), under
`:web/…`. A system using the library reads them from there: fukan finds a used vocabulary on the
classpath, reads that one file as forms, and takes the views declared in it. A view declared
anywhere else in a library is not seen by a system using it. If that system's signature names
such a view, its check reports the name as unresolved.

What it cannot say: anything about a system that uses it. A session belongs to somebody, but
who is the using system's to say. The shop says it with an entity of its own (`Login`) that
refers to both a `:web.session` and a `:customer`.

Where a using system speaks this language is read from signatures alone. fukan's `languages`
reading lists which namespaces speak which vocabulary. Its `translators` reading lists the
functions that speak more than one, which in the shop are only `shop.front`'s two.
