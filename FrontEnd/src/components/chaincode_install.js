/**
 * oxchain ivoice app
 *
 *
 * Author: Jun
 * Email: iyakexi@gmail.com
 * Date: 08/05/2017
 *
 */

import React, { Component } from 'react';
import { Field, reduxForm } from 'redux-form';
import { connect } from 'react-redux';
import { installChainCode } from '../actions/chaincode';
import { fetchPeerList } from '../actions/peer';

class ChainCodeInstall extends Component {
  constructor(props) {
    super(props);
    this.state = { error:null, spin:false, selectedPeers:[] };
  }

  componentWillMount() {
    this.props.fetchPeerList();
  }

  componentWillReceiveProps(props) {
    if(!props.selectedItem) {
      this.setState({error:null});
    }
  }

  handleFormSubmit() {
    if(this.state.selectedPeers.length<1) {
      alert('请选择节点');
      return;
    }
    if(!this.props.selectedItem) return;

    const { name, version, lang } = this.props.selectedItem;
    const  peers = this.state.selectedPeers;
    this.setState({ spin:true });
    this.props.installChainCode({ name, version, lang, peers }, err => {
      this.setState({ error: err ? err : null, spin:false });
      this.props.actionCallback(err);
    });
  }

  handleCheckboxClick(e) {
    const {checked, value} = e.target;
    if(checked) {
      this.setState({selectedPeers: [...this.state.selectedPeers, value]});
    } else {
      this.setState({selectedPeers: this.state.selectedPeers.filter((v) => v!=value)});
    }
  }

  renderAlert() {
    if (this.state.error && this.props.selectedItem) {
      return (
        <div className="alert alert-danger alert-dismissable">
          {this.state.error}
        </div>
      );
    }
  }

  renderField( key ) {
    const { selectedItem } = this.props;
    if(!selectedItem) return <td></td>;
    return (<td>{selectedItem[key]}</td>)
  }

  renderPeers() {
    if(!this.props.peers) return <tr><td>loading</td></tr>;
    return this.props.peers.map((row, idx) => {
      return (<tr key={idx}>
        <td>{row.id}</td>
        <td>{row.status}</td>
        <td>
          <input type="checkbox" value={row.id} onClick={this.handleCheckboxClick.bind(this)}/>
        </td>
      </tr>);
    });
  }

  render() {
    const { handleSubmit } = this.props;
    return (
      <div>
        <div className="">
            {this.renderAlert()}
            <table className="table table-bordered">
          <tbody>
          <tr><th>名称</th><th>版本号</th><th>语言</th></tr>
          <tr>
            {this.renderField('name')}
            {this.renderField('version')}
            {this.renderField('lang')}
          </tr>
          </tbody>
        </table>
            <form className="form" onSubmit={handleSubmit(this.handleFormSubmit.bind(this))}>
              <div className="">
                <table className="table table-bordered">
                  <tbody>
                  <tr><th>节点ID</th><th>状态</th><th>选择</th></tr>
                  {this.renderPeers()}
                  </tbody>
                </table>
              </div>
              <div className="row">
                <div className="col-xs-8">
                </div>
                <div className="col-xs-4">
                  <button type="submit" className="btn btn-primary pull-right"><i className={`fa fa-spinner fa-spin ${this.state.spin?'':'hidden'}`}></i> 提交 </button>
                </div>
              </div>

            </form>
        </div>
      </div>
    );
  }
}


const validate = values => {
  const errors = {};

  return errors
};


const reduxChainCodeInstallForm = reduxForm({
  form: 'installChaincodeForm',
  validate
})(ChainCodeInstall);

function mapStateToProps(state) {
  return {
    peers: state.peer.all
  };
}

export default connect(mapStateToProps, { installChainCode,fetchPeerList })(reduxChainCodeInstallForm);