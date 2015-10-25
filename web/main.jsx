var React = require('react');
var ReactDOM = require('react-dom');
var $ = require('jquery');

var Card = require('material-ui/lib/card/card');
var CardHeader = require('material-ui/lib/card/card-header');
var CardActions = require('material-ui/lib/card/card-actions');
var CardText = require('material-ui/lib/card/card-text');
var DropDownMenu = require('material-ui/lib/drop-down-menu');
var FlatButton = require('material-ui/lib/flat-button');
var LinearProgress = require('material-ui/lib/linear-progress');
var IconButton = require('material-ui/lib/icon-button');
var SelectField = require('material-ui/lib/select-field');
var ClearFix = require('material-ui/lib/clearfix');
var injectTapEventPlugin = require("react-tap-event-plugin");

//Needed for onTouchTap
//Can go away when react 1.0 release
//Check this repo:
//https://github.com/zilverline/react-tap-event-plugin
injectTapEventPlugin();



var URLBox = React.createClass({
    getInitialState: function() {
        return {
            value: '',
            valid: true,
        };
    },
    _style: function() {
        return {
            backgroundColor: this.state.valid ? '#fff' : '#baa',
            width: '80%',
            margin: '1em',
        };
    },
    render: function() {
        // TODO: Include a dropbox of earlier-used URLs, from local db.
        return <input style={this._style()} type="text" value={this.state.value} onChange={this._change} />;
    },
    _change: function(e) {
        var nv = e.target.value;
        this.setState({value: nv});
        if (/^https?:\/\/forums.(sufficientvelocity|spacebattles).com\/threads\/.+/.test(nv)) {
            this.setState({valid: true});
            this.props.update(nv);
        } else {
            this.setState({valid: false});
        }
    }
});

var voteStyle = {
    whiteSpace: 'pre-wrap'
};

var VoteTally = React.createClass({
    getInitialState: function() {
        return {
            url: '',
            progressAt: 0,
            progressTotal: 1,
            progressMode: 'determinate',
            
            threadmarks: [],
            threadmark: 0,

            fetchId: 0,
            election: [],
        };
    },
    _updateUrl: function(url) {
        this.setState({url: url, progressMode: 'indeterminate'});
        $.getJSON('/Threadmarks', {url: url}, this._gotThreadmarks);
    },
    _gotThreadmarks: function(threadmarks) {
        this.setState({
            threadmarks: threadmarks,
            threadmark: threadmarks[0].post,
            progressAt: 0,
            progressTotal: 1,
            progressMode: 'determinate',
        });
        this._fetchThreadmark(threadmarks[0].post);
    },
    _threadSelect: function(e) {
        console.log("Selecting threadmark " + e.target.value);
        this.setState({
            threadmark: e.target.value
        });
        this._fetchThreadmark(e.target.value);
    },
    _fetchThreadmark: function(threadmark, force) {
        // Should we cache the links, or... nah. Computers are fast.
        console.log('Fetching', threadmark, !!force);
        for (var i = 0; i < this.state.threadmarks.length; i++) {
            var t = this.state.threadmarks[i];
            if (t.post == threadmark) {
                var data = {};
                for (var key in t) {
                    data[key] = t[key];
                }
                data.force = !!force;
                $.getJSON('/StartFetch', data, this._startFetch);
            }
        }
    },
    _startFetch: function(id) {
        this.setState({
            fetchId: id.id,
            progressAt: 0,
            progressTotal: 1,
            election: [],
        });
        var self = this;
        setTimeout(function() {
            $.getJSON('/PollFetch', {id: id.id}, self._pollFetch);
        }, 1000);
    },
    _refresh: function() {
        console.log('Asked to refresh');
        this._fetchThreadmark(this.state.threadmark, true);
    },
    _pollFetch: function(progress) {
        if (progress.id != this.state.fetchId) return;
        this.setState({
            progressAt: progress.progressAt,
            progressTotal: progress.progressTotal
        });
        if (progress.result) {
            this.setState({election: progress.result});
        } else {
            var self = this;
            setTimeout(function() {
                $.getJSON('/PollFetch', {id: self.state.fetchId}, self._pollFetch);
            }, 1000);
        }
    },
    render: function() {
        var cards = [];
        for (var i = 0; i < this.state.election.length; i++) {
            var vote = this.state.election[i];
            var voters = [];
            for (var j = 0; j < vote.votes.length; j++) {
                var voter = vote.votes[j];
                voters.push(<FlatButton key={voter.author} label={voter.author} linkButton={true} href={voter.href} />);
            }
            var card = <Card key={vote.votes[0].author} initiallyExpanded={true}>
                         <CardHeader
                             title={"Plan " + vote.votes[0].author}
                             subtitle={vote.weight + " votes"}
                             actAsExpander={true}
                             showExpandableButton={true}
                         />
                         <CardActions>{voters}</CardActions>
                         <CardText expandable={true}><pre style={voteStyle}>{vote.text}</pre></CardText>
                       </Card>;
            cards.push(card);
        }
        return (
            <div>
                <LinearProgress mode={this.state.progressMode} min={0} max={this.state.progressTotal} value={this.state.progressAt} />
                <URLBox update={this._updateUrl} />
                <IconButton onClick={this._refresh} iconClassName="material-icons" tooltipPosition="bottom-center">refresh</IconButton>
                <SelectField value={this.state.threadmark} autoWidth={true} menuItems={this.state.threadmarks} displayMember="title" valueMember="post" onChange={this._threadSelect} />
                {cards}
            </div>
        );
    }
});


ReactDOM.render(<VoteTally />, document.getElementById("content"));
