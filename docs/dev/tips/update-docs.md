---
layout: page
title: Updating the Docs
toc: /toc.json
---

The Brooklyn docs live in the **docs** project in the Brooklyn codebase.
It's built using standard jekyll/markdown with a few extensions.


## Jekyll

Install the following:

* [**Jekyll**](https://github.com/mojombo/jekyll/wiki/install): `sudo gem install jekyll`
* [**JSON gem**](http://rubygems.org/gems/json): `sudo gem install json`
* [**RDiscount**](https://github.com/rtomayko/rdiscount/#readme): `sudo gem install rdiscount`
* [**Pygments**](http://pygments.org/): `sudo easy_install Pygments`

Then, in the `docs/` directory, run:
	
	jekyll --pygments --server --auto --url ""
or 
    ./_scripts/jekyll-debug.sh 
    
Visit [http://localhost:4000/](http://localhost:4000/) and you should see the documentation.


## Extensions

In addition to the standard pygments plugin for code-highlighting,
we use some custom Jekyll plugins (in the `_plugins` dir) to:

* include markdown files inside other files 
  (see, for example, the `*.include.md` files which contain text
  which is used in multiple other files)
* parse JSON which we can loop over in our markdown docs
* trim whitespace of ends of variables

Using JSON table-of-contents files (`toc.json`) is our lightweight solution
to the problem of making the site structure navigable (the menus at left).
If you add a page, simply add the file (with full path from project root)
and a title to the toc.json in that directory, and it will get included
in the menu.  You can also configure a special toc to show on your page,
if you wish, by setting the toc variable in the header.
Most pages declare the "page" layout (`_layouts/page.html`) which builds
a menu in the left side-bar (`_includes/sidebar.html`) using the JSON --
and automatically detecting which page is active. 
 

## Publishing

Because GitHub don't run plugins (they run with the `--safe` option),
the site is built off-line and uploaded to github, where the documentation is hosted.

This makes the process a little more tedious, but it does have the advantage 
that the documentation lives right in the Brooklyn project,
easy to open alongside the code inside your IDE.

The off-line build can be done using `/docs/_scripts/build.sh`,
including both jekyll markdown documentation and Brooklyn javadoc,
with the result of this copied to the `brooklyncentral/brooklyncentral.github.com` 
github project (as per the GitHub pages documentation).
[brooklyn.io](http://brooklyn.io) is CNAMEd to [brooklyncentral.github.com](brooklyncentral.github.com)
for convenience.

The latest stable version typically lives in the root of the `brooklyncentral.github.com` project.
Archived versions are kept under `/v/*` with logic in the markdown for 
[meta/versions]({{ site.url }}/meta/versions.html) to link to related versions.  
Additional instructions and scripts for automating the installs can be found in `/docs/_scripts/`.

